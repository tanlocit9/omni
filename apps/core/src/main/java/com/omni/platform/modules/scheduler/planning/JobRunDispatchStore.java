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
        List<UUID> ids =
                jdbc.queryForList(
                        """
                SELECT o.id FROM scheduler_outbox_messages o
                JOIN job_execution_histories h ON h.id=o.execution_id
                JOIN job_run_steps s ON s.job_run_id=h.job_run_id AND s.step_index=h.job_run_step_index
                WHERE s.job_run_id=:run AND s.step_index=:step AND s.job_definition_id=:definition
                  AND s.scope_type=:type AND s.scope_key=:key AND s.business_date=:date
                  AND o.status='PENDING' AND o.available_at <= :now
                  AND (o.claim_until IS NULL OR o.claim_until <= :now)
                ORDER BY o.available_at,o.created_at,o.id LIMIT :limit
                """,
                        parameters,
                        UUID.class);
        return outbox.findCandidatesByIds(ids);
    }

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
        return outbox.findCandidatesByIds(ids);
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
