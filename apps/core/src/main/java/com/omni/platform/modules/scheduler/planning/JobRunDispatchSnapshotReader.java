package com.omni.platform.modules.scheduler.planning;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Membership;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Outbox;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Run;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;

/** Explicit bounded run reads only. Does not discover work, claim rows or publish messages. */
@Repository
public class JobRunDispatchSnapshotReader {
    public static final int MAX_RUNS = 32;
    public static final int MAX_STEPS = 1024;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JobRunDispatchSnapshotReader(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    /** A missing/oversized/unsupported run is incomplete, never an empty successful workflow. */
    public JobRunDispatchSnapshot capture(Instant at, List<UUID> requestedRuns) {
        Objects.requireNonNull(at, "at");
        List<UUID> ids = List.copyOf(requestedRuns);
        if (ids.isEmpty()
                || ids.size() > MAX_RUNS
                || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("Expected 1..32 distinct JobRun IDs");
        }
        return Objects.requireNonNull(transaction.execute(status -> read(at, ids)));
    }

    private JobRunDispatchSnapshot read(Instant at, List<UUID> ids) {
        var parameters =
                new MapSqlParameterSource("ids", ids).addValue("at", java.sql.Timestamp.from(at));
        List<Header> headers =
                jdbc.query(
                        """
                SELECT id, state, step_count, path_contract_version FROM job_runs
                WHERE id IN (:ids) ORDER BY requested_at, id
                """,
                        parameters,
                        (rs, row) ->
                                new Header(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("state"),
                                        rs.getInt("step_count"),
                                        rs.getInt("path_contract_version")));
        long expected = headers.stream().mapToLong(Header::steps).sum();
        if (expected > MAX_STEPS
                || headers.stream().anyMatch(h -> h.steps() < 1 || h.version() != 1)) {
            return new JobRunDispatchSnapshot(at, false, List.of());
        }
        Map<Key, List<Integer>> predecessors = new HashMap<>();
        jdbc.query(
                """
                SELECT job_run_id, step_index, predecessor_index FROM job_run_step_predecessors
                WHERE job_run_id IN (:ids) ORDER BY job_run_id, step_index, predecessor_index
                LIMIT 8193
                """,
                parameters,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs ->
                                predecessors
                                        .computeIfAbsent(key(rs), ignored -> new ArrayList<>())
                                        .add(rs.getInt("predecessor_index")));
        int edges = predecessors.values().stream().mapToInt(List::size).sum();
        if (edges > 8192) return new JobRunDispatchSnapshot(at, false, List.of());
        Map<UUID, List<Step>> steps = new HashMap<>();
        jdbc.query(
                STEPS_SQL,
                parameters,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> {
                            Key key = key(rs);
                            var membership =
                                    new Membership(
                                            rs.getBoolean("closed"),
                                            rs.getBoolean("identities_match"),
                                            rs.getInt("expected_children"),
                                            rs.getInt("expected_messages"),
                                            rs.getLong("roots"),
                                            rs.getLong("executions"),
                                            rs.getLong("successful"),
                                            rs.getLong("failed"),
                                            rs.getLong("unknown_executions"));
                            var outbox =
                                    new Outbox(
                                            rs.getLong("eligible"),
                                            rs.getLong("leased"),
                                            rs.getLong("retry_delayed"),
                                            rs.getLong("published"),
                                            rs.getLong("blocked"),
                                            rs.getLong("unknown_messages"));
                            var scope =
                                    new Scope(
                                            ScopeType.valueOf(rs.getString("scope_type")),
                                            rs.getString("scope_key"),
                                            rs.getObject("business_date", LocalDate.class));
                            steps.computeIfAbsent(key.run(), ignored -> new ArrayList<>())
                                    .add(
                                            new Step(
                                                    key.index(),
                                                    rs.getObject("job_definition_id", UUID.class),
                                                    scope,
                                                    predecessors.getOrDefault(key, List.of()),
                                                    membership,
                                                    outbox));
                        });
        List<Run> runs =
                headers.stream()
                        .map(
                                header ->
                                        new Run(
                                                header.id(),
                                                header.state(),
                                                header.steps(),
                                                steps.getOrDefault(header.id(), List.of())))
                        .toList();
        boolean complete =
                headers.size() == ids.size()
                        && runs.stream().mapToLong(run -> run.steps().size()).sum() == expected;
        return new JobRunDispatchSnapshot(at, complete, runs);
    }

    /** Admission already holds membership/status locks on the caller's connection. */
    JobRunDispatchSnapshot captureLocked(Instant at, UUID runId) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException("Admission snapshot requires a transaction");
        }
        return read(at, List.of(runId));
    }

    private static Key key(ResultSet rs) throws SQLException {
        return new Key(rs.getObject("job_run_id", UUID.class), rs.getInt("step_index"));
    }

    private record Header(UUID id, String state, int steps, int version) {}

    private record Key(UUID run, int index) {}

    // Each aggregate starts from the frozen (job_run_id, step_index) key. Separate
    // lateral aggregates avoid multiplying execution counts by outbox/predecessor joins.
    // Existing V12/V13 indexes and immutable membership cap per-step fan-out at 5000.
    static final String STEPS_SQL =
            """
            WITH selected_steps AS MATERIALIZED (
                SELECT * FROM job_run_steps WHERE job_run_id IN (:ids)
                ORDER BY job_run_id, step_index LIMIT 1025
            )
            SELECT s.job_run_id, s.step_index, s.job_definition_id, s.scope_type, s.scope_key, s.business_date,
                (p.closed_at IS NOT NULL AND p.closed_at <= :at) AS closed,
                coalesce(p.child_count, -1) AS expected_children, coalesce(p.message_count, -1) AS expected_messages,
                e.roots, e.executions, e.successful, e.failed, e.unknown_executions,
                coalesce(e.identities_match, false) AS identities_match,
                o.eligible, o.leased, o.retry_delayed, o.published, o.blocked, o.unknown_messages
            FROM selected_steps s
            LEFT JOIN job_run_step_preparations p ON p.job_run_id=s.job_run_id AND p.step_index=s.step_index
            CROSS JOIN LATERAL (
                SELECT count(*) AS executions,
                    count(*) FILTER (WHERE h.parent_log_id IS NULL) AS roots,
                    count(*) FILTER (WHERE h.status='SUCCESS') AS successful,
                    count(*) FILTER (WHERE h.status IN ('FAILED','ERROR','BLOCKED')) AS failed,
                    count(*) FILTER (WHERE h.status IS NULL OR h.status NOT IN
                        ('PENDING','RUNNING','SUCCESS','FAILED','ERROR','BLOCKED')) AS unknown_executions,
                    bool_and(coalesce(h.job_id=s.job_definition_id AND h.meta_json->>'runPreparationVersion'='1'
                        AND h.meta_json->>'businessDate'=s.business_date::text
                        AND ((h.id=p.parent_execution_id AND h.parent_log_id IS NULL)
                            OR h.parent_log_id=p.parent_execution_id), false)) AS identities_match
                FROM job_execution_histories h
                WHERE p.closed_at IS NOT NULL
                    AND h.job_run_id=s.job_run_id AND h.job_run_step_index=s.step_index
            ) e
            CROSS JOIN LATERAL (
                SELECT count(*) FILTER (WHERE m.status='PENDING' AND m.available_at <= :at
                        AND (m.claim_until IS NULL OR m.claim_until <= :at)) AS eligible,
                    count(*) FILTER (WHERE m.status='PENDING' AND m.claim_until > :at) AS leased,
                    count(*) FILTER (WHERE m.status='PENDING' AND m.available_at > :at
                        AND (m.claim_until IS NULL OR m.claim_until <= :at)) AS retry_delayed,
                    count(*) FILTER (WHERE m.status='PUBLISHED') AS published,
                    count(*) FILTER (WHERE m.status='BLOCKED') AS blocked,
                    count(*) FILTER (WHERE m.status IS NULL OR m.status NOT IN ('PENDING','PUBLISHED','BLOCKED')) AS unknown_messages
                FROM job_execution_histories h JOIN scheduler_outbox_messages m ON m.execution_id=h.id
                WHERE p.closed_at IS NOT NULL
                    AND h.job_run_id=s.job_run_id AND h.job_run_step_index=s.step_index
            ) o
            ORDER BY s.job_run_id, s.step_index
            """;
}
