package com.omni.platform.modules.scheduler.repositories;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.runs.FrozenJobRunPath;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.State;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.RequiredPath;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

import tools.jackson.databind.json.JsonMapper;

/** Inactive I3.b storage boundary. No scheduler, controller or producer calls this repository. */
@Repository
public class JobRunRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public JobRunRepository(
            JdbcTemplate jdbc, PlatformTransactionManager manager, JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
    }

    /** Freeze a caller-resolved path atomically; this neither prepares nor dispatches executions. */
    public JobRunSnapshot prepare(Request request, RequiredPath resolvedPath) {
        if (request.mode() == Mode.BACKFILL) {
            throw new IllegalArgumentException("BACKFILL preparation is reserved, not enabled");
        }
        if (!json.readTree(request.parametersJson()).isObject()) {
            throw new IllegalArgumentException("Request parameters must be a JSON object");
        }
        FrozenJobRunPath frozen = FrozenJobRunPath.freeze(resolvedPath);
        return Objects.requireNonNull(
                transactions.execute(
                        transaction -> {
                            var existing = requestId(request);
                            if (existing.isPresent())
                                return replay(existing.get(), request, frozen);
                            List<UUID> definitions = resolveDefinitions(resolvedPath);
                            UUID id = UUID.randomUUID();
                            var scope = resolvedPath.target().scope();
                            int inserted =
                                    jdbc.update(
                                            """
                    INSERT INTO job_runs (id, mode, actor, idempotency_key, reason, business_group,
                        request_json, target_definition_id, scope_type, scope_key, business_date,
                        path_contract_version, path_fingerprint, step_count)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (mode, actor, idempotency_key) DO NOTHING
                    """,
                                            id,
                                            request.mode().name(),
                                            request.actor(),
                                            request.idempotencyKey(),
                                            request.reason(),
                                            request.businessGroup(),
                                            request.parametersJson(),
                                            definitions.getLast(),
                                            scope.type().name(),
                                            scope.key(),
                                            scope.businessDate(),
                                            resolvedPath.contractVersion(),
                                            frozen.fingerprint(),
                                            definitions.size());
                            if (inserted == 0)
                                return replay(requestId(request).orElseThrow(), request, frozen);
                            List<Object[]> steps = new ArrayList<>();
                            List<Object[]> edges = new ArrayList<>();
                            for (int index = 0; index < definitions.size(); index++) {
                                var key = resolvedPath.steps().get(index).key();
                                var definition = key.definition();
                                steps.add(
                                        new Object[] {
                                            id,
                                            index,
                                            definitions.get(index),
                                            definition.source().name(),
                                            definition.jobType().name(),
                                            definition.cronExpression(),
                                            key.scope().type().name(),
                                            key.scope().key(),
                                            key.scope().businessDate()
                                        });
                                for (int upstream : frozen.predecessors().get(index)) {
                                    edges.add(new Object[] {id, index, upstream});
                                }
                            }
                            jdbc.batchUpdate(
                                    """
                    INSERT INTO job_run_steps (job_run_id, step_index, job_definition_id,
                        definition_source, definition_job_type, definition_cron, scope_type, scope_key, business_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                                    steps);
                            if (!edges.isEmpty()) {
                                jdbc.batchUpdate(
                                        """
                        INSERT INTO job_run_step_predecessors (job_run_id, step_index, predecessor_index)
                        VALUES (?, ?, ?)
                        """,
                                        edges);
                            }
                            jdbc.update("UPDATE job_runs SET state = 'PREPARED' WHERE id = ?", id);
                            return findById(id).orElseThrow();
                        }));
    }

    private Optional<UUID> requestId(Request request) {
        return jdbc
                .query(
                        "SELECT id FROM job_runs WHERE mode = ? AND actor = ? AND idempotency_key = ?",
                        (rs, row) -> rs.getObject("id", UUID.class),
                        request.mode().name(),
                        request.actor(),
                        request.idempotencyKey())
                .stream()
                .findFirst();
    }

    private JobRunSnapshot replay(UUID id, Request request, FrozenJobRunPath frozen) {
        Boolean matches =
                jdbc.queryForObject(
                        """
                SELECT reason = ? AND business_group IS NOT DISTINCT FROM CAST(? AS varchar)
                    AND request_json = CAST(? AS jsonb) AND path_fingerprint = ?
                FROM job_runs WHERE id = ?
                """,
                        Boolean.class,
                        request.reason(),
                        request.businessGroup(),
                        request.parametersJson(),
                        frozen.fingerprint(),
                        id);
        if (!Boolean.TRUE.equals(matches)) {
            throw new IllegalArgumentException(
                    "JOB_RUN_IDEMPOTENCY_CONFLICT: request or path changed");
        }
        return findById(id).orElseThrow();
    }

    private List<UUID> resolveDefinitions(RequiredPath path) {
        List<JobDefinitionIdentity> identities =
                path.steps().stream().map(step -> step.key().definition()).distinct().toList();
        List<Object> parameters = new ArrayList<>();
        identities.forEach(
                identity ->
                        Collections.addAll(
                                parameters,
                                identity.source().name(),
                                identity.jobType().name(),
                                identity.cronExpression()));
        String placeholders = String.join(",", Collections.nCopies(identities.size(), "(?, ?, ?)"));
        Map<JobDefinitionIdentity, UUID> ids = new HashMap<>();
        jdbc.query(
                "SELECT id, source, job_type, cron_expr FROM job_definitions WHERE "
                        + "(source, job_type, cron_expr) IN ("
                        + placeholders
                        + ")",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs ->
                                ids.put(
                                        new JobDefinitionIdentity(
                                                DataSource.valueOf(rs.getString("source")),
                                                JobType.valueOf(rs.getString("job_type")),
                                                rs.getString("cron_expr")),
                                        rs.getObject("id", UUID.class)),
                parameters.toArray());
        return path.steps().stream()
                .map(
                        step -> {
                            UUID id = ids.get(step.key().definition());
                            if (id == null)
                                throw new IllegalArgumentException(
                                        "Unpersisted path definition: " + step.key().definition());
                            return id;
                        })
                .toList();
    }

    /** Reconstruct exclusively from frozen rows; never consult current static edges or seed config. */
    public Optional<JobRunSnapshot> findById(UUID id) {
        var headers =
                jdbc.query(
                        "SELECT * FROM job_runs WHERE id = ?",
                        (rs, row) ->
                                new Header(
                                        new Request(
                                                Mode.valueOf(rs.getString("mode")),
                                                rs.getString("actor"),
                                                rs.getString("idempotency_key"),
                                                rs.getString("reason"),
                                                rs.getString("business_group"),
                                                rs.getString("request_json")),
                                        State.valueOf(rs.getString("state")),
                                        rs.getString("failure_reason"),
                                        rs.getTimestamp("requested_at").toInstant(),
                                        rs.getInt("path_contract_version"),
                                        rs.getString("path_fingerprint"),
                                        rs.getInt("step_count")),
                        id);
        if (headers.isEmpty()) return Optional.empty();
        Header header = headers.getFirst();
        List<StoredStep> stored =
                jdbc.query(
                        "SELECT * FROM job_run_steps WHERE job_run_id = ? ORDER BY step_index",
                        (rs, row) ->
                                new StoredStep(
                                        rs.getInt("step_index"),
                                        rs.getObject("job_definition_id", UUID.class),
                                        storedKey(rs)),
                        id);
        if (stored.size() != header.count())
            throw new IllegalStateException("Incomplete stored path");
        Map<Integer, List<Integer>> upstream = new HashMap<>();
        jdbc.query(
                """
                SELECT step_index, predecessor_index FROM job_run_step_predecessors
                WHERE job_run_id = ? ORDER BY step_index, predecessor_index
                """,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs ->
                                upstream.computeIfAbsent(
                                                rs.getInt("step_index"),
                                                ignored -> new ArrayList<>())
                                        .add(rs.getInt("predecessor_index")),
                id);
        List<Step> steps = new ArrayList<>();
        for (int index = 0; index < stored.size(); index++) {
            StoredStep row = stored.get(index);
            if (row.index() != index) throw new IllegalStateException("Noncontiguous stored path");
            steps.add(
                    new Step(
                            row.key(),
                            TopologyNodeKey.forJobType(row.key().definition().jobType()),
                            upstream.getOrDefault(index, List.of()).stream()
                                    .map(previous -> stored.get(previous).key())
                                    .toList()));
        }
        FrozenJobRunPath path =
                FrozenJobRunPath.freeze(
                        new RequiredPath(header.version(), steps.getLast().key(), steps));
        if (!path.fingerprint().equals(header.fingerprint()))
            throw new IllegalStateException("Stored path fingerprint mismatch");
        return Optional.of(
                new JobRunSnapshot(
                        id,
                        header.request(),
                        header.state(),
                        header.failure(),
                        header.requestedAt(),
                        path,
                        stored.stream().map(StoredStep::definitionId).toList()));
    }

    private static StepKey storedKey(ResultSet rs) throws SQLException {
        return new StepKey(
                new JobDefinitionIdentity(
                        DataSource.valueOf(rs.getString("definition_source")),
                        JobType.valueOf(rs.getString("definition_job_type")),
                        rs.getString("definition_cron")),
                new Scope(
                        ScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("scope_key"),
                        rs.getDate("business_date").toLocalDate()));
    }

    private record StoredStep(int index, UUID definitionId, StepKey key) {}

    private record Header(
            Request request,
            State state,
            String failure,
            java.time.Instant requestedAt,
            int version,
            String fingerprint,
            int count) {}
}
