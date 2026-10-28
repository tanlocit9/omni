package com.omni.platform.modules.scheduler.planning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchPlanner.Selection;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Progress;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxCandidate;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;

/** Bounded SQL selection plus transaction-local completion revalidation; never publishes Kafka. */
@Service
public class JobRunDispatchStore {
    private static final int MAX_CANDIDATES = 400;
    private static final int MAX_LOCKED_EXECUTIONS = 21024;
    private final NamedParameterJdbcTemplate jdbc;
    private final JobRunDispatchSnapshotReader snapshots;
    private final SchedulerOutboxService outbox;

    public JobRunDispatchStore(
            JdbcTemplate jdbc,
            JobRunDispatchSnapshotReader snapshots,
            SchedulerOutboxService outbox) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.snapshots = snapshots;
        this.outbox = outbox;
    }

    /** UUID keyset rotation is advisory only; state, membership and claims stay in PostgreSQL. */
    @Transactional(readOnly = true)
    public List<UUID> findRunPage(Instant now, UUID after, int limit) {
        if (limit < 1 || limit > JobRunDispatchSnapshotReader.MAX_RUNS)
            throw new IllegalArgumentException("Run page must be 1..32");
        var parameters = parameters(now).addValue("after", after).addValue("limit", limit);
        List<UUID> runs =
                new ArrayList<>(
                        jdbc.queryForList(
                                """
                SELECT r.id FROM job_runs r WHERE r.state IN ('PREPARED','RUNNING')
                  AND (CAST(:after AS uuid) IS NULL OR r.id > :after)
                  AND EXISTS (SELECT 1 FROM job_execution_histories h JOIN scheduler_outbox_messages o ON o.execution_id=h.id
                    WHERE h.job_run_id=r.id AND o.status='PENDING' AND o.available_at <= :now
                      AND (o.claim_until IS NULL OR o.claim_until <= :now))
                ORDER BY r.id LIMIT :limit
                """,
                                parameters,
                                UUID.class));
        if (after != null && runs.size() < limit) {
            parameters.addValue("limit", limit - runs.size());
            runs.addAll(
                    jdbc.queryForList(
                            """
                    SELECT r.id FROM job_runs r WHERE r.state IN ('PREPARED','RUNNING') AND r.id <= :after
                      AND EXISTS (SELECT 1 FROM job_execution_histories h JOIN scheduler_outbox_messages o ON o.execution_id=h.id
                        WHERE h.job_run_id=r.id AND o.status='PENDING' AND o.available_at <= :now
                          AND (o.claim_until IS NULL OR o.claim_until <= :now))
                    ORDER BY r.id LIMIT :limit
                    """,
                            parameters,
                            UUID.class));
        }
        return List.copyOf(runs);
    }

    @Transactional(readOnly = true)
    public List<SchedulerOutboxCandidate> findScopedCandidates(
            Selection selection, Instant now, int limit) {
        bounds(limit);
        var parameters = scope(selection, now).addValue("limit", limit);
        List<UUID> ids = jdbc.queryForList(SCOPED_IDS_SQL, parameters, UUID.class);
        return outbox.findCandidatesByIdsSafely(ids);
    }

    private static final String SCOPED_IDS_SQL =
            """
                SELECT o.id FROM scheduler_outbox_messages o
                JOIN job_execution_histories h ON h.id=o.execution_id
                JOIN job_run_steps s ON s.job_run_id=h.job_run_id AND s.step_index=h.job_run_step_index
                WHERE s.job_run_id=:run AND s.step_index=:step AND s.job_definition_id=:definition
                  AND s.scope_type=:type AND s.scope_key=:key AND s.business_date=:date
                  AND o.status='PENDING' AND o.available_at <= :now
                  AND (o.claim_until IS NULL OR o.claim_until <= :now)
                ORDER BY o.available_at,o.created_at,o.id LIMIT :limit
                """;

    @Transactional(readOnly = true)
    public List<SchedulerOutboxCandidate> findLegacyCandidates(Instant now, int limit) {
        bounds(limit);
        List<UUID> ids =
                jdbc.queryForList(
                        """
                SELECT o.id FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id
                WHERE h.job_run_id IS NULL AND o.status='PENDING' AND o.available_at <= :now
                  AND (o.claim_until IS NULL OR o.claim_until <= :now)
                ORDER BY o.available_at,o.created_at,o.id LIMIT :limit
                """,
                        parameters(now).addValue("limit", limit),
                        UUID.class);
        return outbox.findCandidatesByIdsSafely(ids);
    }

    /** Run lock -> roots -> children -> outbox, all SKIP LOCKED, before metadata or attempts change. */
    @Transactional(timeout = 5)
    public SchedulerOutboxClaim claimPlanned(
            Selection selected,
            UUID messageId,
            Instant now,
            String instanceId,
            Duration lease,
            Map<DatasetRef, String> approved) {
        var parameters = scope(selected, now).addValue("message", messageId);
        var runs =
                jdbc.queryForList(
                        """
                SELECT id FROM job_runs WHERE id=:run AND state IN ('PREPARED','RUNNING')
                FOR UPDATE SKIP LOCKED
                """,
                        parameters,
                        UUID.class);
        if (runs.isEmpty()) return null;
        var required =
                jdbc.query(
                        """
                WITH RECURSIVE required(step_index) AS (
                    SELECT CAST(:step AS integer)
                    UNION
                    SELECT p.predecessor_index FROM job_run_step_predecessors p JOIN required r ON r.step_index=p.step_index
                    WHERE p.job_run_id=:run
                )
                SELECT s.step_index,p.child_count FROM required r
                JOIN job_run_steps s ON s.job_run_id=:run AND s.step_index=r.step_index
                LEFT JOIN job_run_step_preparations p ON p.job_run_id=s.job_run_id AND p.step_index=s.step_index
                ORDER BY s.step_index
                """,
                        parameters,
                        (rs, row) -> new Required(rs.getInt(1), (Integer) rs.getObject(2)));
        if (required.isEmpty() || required.stream().anyMatch(step -> step.children() == null))
            return null;
        long expected = required.stream().mapToLong(step -> step.children() + 1L).sum();
        if (expected > MAX_LOCKED_EXECUTIONS) return null;
        parameters.addValue("steps", required.stream().map(Required::index).toList());
        List<UUID> locked =
                jdbc.queryForList(
                        """
                SELECT id FROM job_execution_histories WHERE job_run_id=:run AND job_run_step_index IN (:steps)
                ORDER BY (parent_log_id IS NOT NULL),job_run_step_index,id
                LIMIT 21025 FOR SHARE SKIP LOCKED
                """,
                        parameters,
                        UUID.class);
        if (locked.size() != expected) return null;
        var snapshot = snapshots.captureLocked(now, selected.jobRunId());
        if (!snapshot.complete() || snapshot.runs().isEmpty()) return null;
        var run = snapshot.runs().getFirst();
        if (!run.complete()
                || selected.stepIndex() < 0
                || selected.stepIndex() >= run.steps().size()) return null;
        var step = run.steps().get(selected.stepIndex());
        if (!step.definitionId().equals(selected.definitionId())
                || !step.scope().equals(selected.scope())
                || step.progress() != Progress.UNFINISHED
                || !JobRunDispatchPlanner.predecessorsSucceeded(run, step.index())) return null;
        List<UUID> messages =
                jdbc.queryForList(
                        """
                SELECT o.id FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id
                WHERE o.id=:message AND h.job_run_id=:run AND h.job_run_step_index=:step
                  AND o.status='PENDING' AND o.available_at <= :now
                  AND (o.claim_until IS NULL OR o.claim_until <= :now)
                FOR UPDATE OF o SKIP LOCKED
                """,
                        parameters,
                        UUID.class);
        if (messages.isEmpty()) return null;
        return outbox.claimEligible(messageId, now, instanceId, lease, approved);
    }

    private static MapSqlParameterSource parameters(Instant now) {
        return new MapSqlParameterSource(
                "now", java.sql.Timestamp.from(Objects.requireNonNull(now)));
    }

    /** Guard legacy identity too: a newly linked row cannot bypass enabled JobRun admission. */
    @Transactional(timeout = 5)
    public SchedulerOutboxClaim claimLegacy(
            UUID messageId,
            Instant now,
            String owner,
            Duration lease,
            Map<DatasetRef, String> approved) {
        var parameters = parameters(now).addValue("message", messageId);
        var executions =
                jdbc.queryForList(
                        """
                SELECT h.id FROM job_execution_histories h JOIN scheduler_outbox_messages o ON o.execution_id=h.id
                WHERE o.id=:message AND h.job_run_id IS NULL FOR UPDATE OF h SKIP LOCKED
                """,
                        parameters,
                        UUID.class);
        if (executions.isEmpty()) return null;
        var messages =
                jdbc.queryForList(
                        """
                SELECT id FROM scheduler_outbox_messages WHERE id=:message AND status='PENDING'
                  AND available_at <= :now AND (claim_until IS NULL OR claim_until <= :now)
                FOR UPDATE SKIP LOCKED
                """,
                        parameters,
                        UUID.class);
        if (messages.isEmpty()) return null;
        // V15 makes the FK the actual work row. The first nonwaiting write lock protects
        // approved-input metadata and preserves applyStatus's child-before-parent order.
        if (outbox.findCandidatesByIdsSafely(List.of(messageId)).isEmpty()) return null;
        return outbox.claimEligible(messageId, now, owner, lease, approved);
    }

    /** Bounded FIFO pagination for fallback; each linked candidate still passes claimPlanned. */
    @Transactional(readOnly = true)
    public FallbackBatch findFallbackCandidates(Instant now, FallbackCursor after, int limit) {
        bounds(limit);
        List<FallbackRow> rows = new ArrayList<>(fallbackRows(now, after, false, limit));
        if (after != null && rows.size() < limit)
            rows.addAll(fallbackRows(now, after, true, limit - rows.size()));
        if (rows.isEmpty()) return new FallbackBatch(List.of(), after);
        var candidates =
                outbox.findCandidatesByIdsSafely(
                        rows.stream().map(FallbackRow::messageId).toList());
        Map<UUID, SchedulerOutboxCandidate> loaded = new java.util.HashMap<>();
        candidates.forEach(candidate -> loaded.put(candidate.messageId(), candidate));
        List<PlannedCandidate> result = new ArrayList<>();
        for (var row : rows) {
            var candidate = loaded.get(row.messageId());
            if (candidate != null) result.add(new PlannedCandidate(candidate, row.selection()));
        }
        return new FallbackBatch(result, rows.getLast().cursor());
    }

    private List<FallbackRow> fallbackRows(
            Instant now, FallbackCursor after, boolean wrap, int limit) {
        String comparison =
                after == null
                        ? ""
                        : " AND (o.available_at,o.created_at,o.id) "
                                + (wrap ? "<=" : ">")
                                + " (:available,:created,:id)";
        var parameters = parameters(now).addValue("limit", limit);
        if (after != null)
            parameters
                    .addValue("available", java.sql.Timestamp.from(after.availableAt()))
                    .addValue("created", java.sql.Timestamp.from(after.createdAt()))
                    .addValue("id", after.messageId());
        return jdbc.query(
                """
                SELECT o.id,h.job_run_id,h.job_run_step_index,s.job_definition_id,s.scope_type,s.scope_key,s.business_date,
                    o.available_at,o.created_at FROM scheduler_outbox_messages o
                JOIN job_execution_histories h ON h.id=o.execution_id
                LEFT JOIN job_run_steps s ON s.job_run_id=h.job_run_id AND s.step_index=h.job_run_step_index
                WHERE o.status='PENDING' AND o.available_at <= :now
                  AND (o.claim_until IS NULL OR o.claim_until <= :now)
                """
                        + comparison
                        + " ORDER BY o.available_at,o.created_at,o.id LIMIT :limit",
                parameters,
                (rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class),
                            run = rs.getObject("job_run_id", UUID.class);
                    Selection selection =
                            run == null
                                    ? null
                                    : new Selection(
                                            run,
                                            rs.getInt("job_run_step_index"),
                                            rs.getObject("job_definition_id", UUID.class),
                                            new com.omni.platform.modules.scheduler.topology
                                                    .RequiredPathResolver.Scope(
                                                    com.omni.platform.modules.scheduler.topology
                                                            .RequiredPathResolver.ScopeType.valueOf(
                                                            rs.getString("scope_type")),
                                                    rs.getString("scope_key"),
                                                    rs.getObject(
                                                            "business_date",
                                                            java.time.LocalDate.class)),
                                            1,
                                            JobRunDispatchPlanner.Reason.ROOT_STEP);
                    return new FallbackRow(
                            id,
                            selection,
                            new FallbackCursor(
                                    rs.getTimestamp("available_at").toInstant(),
                                    rs.getTimestamp("created_at").toInstant(),
                                    id));
                });
    }

    public record PlannedCandidate(SchedulerOutboxCandidate candidate, Selection selection) {}

    public record FallbackCursor(Instant availableAt, Instant createdAt, UUID messageId) {}

    public record FallbackBatch(List<PlannedCandidate> candidates, FallbackCursor nextCursor) {
        public FallbackBatch {
            candidates = List.copyOf(candidates);
        }
    }

    private record FallbackRow(UUID messageId, Selection selection, FallbackCursor cursor) {}

    private static MapSqlParameterSource scope(Selection selection, Instant now) {
        Objects.requireNonNull(selection);
        if (selection.stepIndex() < 0 || selection.stepIndex() >= 1024)
            throw new IllegalArgumentException("Invalid step index");
        return parameters(now)
                .addValue("run", selection.jobRunId())
                .addValue("step", selection.stepIndex())
                .addValue("definition", selection.definitionId())
                .addValue("type", selection.scope().type().name())
                .addValue("key", selection.scope().key())
                .addValue("date", selection.scope().businessDate());
    }

    private static void bounds(int limit) {
        if (limit < 1 || limit > MAX_CANDIDATES)
            throw new IllegalArgumentException("Candidate limit must be 1..400");
    }

    private record Required(int index, Integer children) {}
}
