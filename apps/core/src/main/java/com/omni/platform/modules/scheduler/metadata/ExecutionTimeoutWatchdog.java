package com.omni.platform.modules.scheduler.metadata;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkType;
import tools.jackson.databind.json.JsonMapper;

/** Published deadline only; timeout never proves that the worker has stopped writing. */
@Component
@ConditionalOnProperty(name = "app.output-registration.watchdog-enabled", havingValue = "true")
public class ExecutionTimeoutWatchdog {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final JobService jobs;
    private final TransactionTemplate transactions;
    private final Duration timeout;
    private final int batchSize;
    private volatile UUID cursor;

    public ExecutionTimeoutWatchdog(JdbcTemplate jdbc, JsonMapper json, JobService jobs,
            org.springframework.transaction.PlatformTransactionManager manager,
            @Value("${app.output-registration.timeout:PT30M}") Duration timeout,
            @Value("${app.output-registration.watchdog-delay-ms:120000}") long delay,
            @Value("${app.output-registration.watchdog-batch-size:100}") int batchSize) {
        if (timeout.isNegative() || timeout.isZero() || delay < 60000 || delay > 300000
                || batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid watchdog bounds");
        this.jdbc = jdbc;
        this.json = json;
        this.jobs = jobs;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(15);
        this.timeout = timeout;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.output-registration.watchdog-delay-ms:120000}")
    public void tick() { scan(Instant.now()); }

    public int scan(Instant now) {
        UUID after = cursor; // Advisory per-instance pagination; correctness comes from row locks.
        var ids = jdbc.queryForList("SELECT e.id FROM job_execution_histories e "
                + "JOIN atomic_work_attempts a ON a.execution_id=e.id AND a.sealed "
                + "WHERE e.status IN ('PENDING','RUNNING') AND e.parent_log_id IS NOT NULL "
                + "AND (?::uuid IS NULL OR e.id>?::uuid) "
                + "AND EXISTS(SELECT 1 FROM scheduler_outbox_messages o WHERE o.execution_id=e.id "
                + "AND o.status='PUBLISHED' AND o.published_at<=?) "
                + "AND NOT EXISTS(SELECT 1 FROM scheduler_outbox_messages o WHERE o.execution_id=e.id "
                + "AND (o.status<>'PUBLISHED' OR o.published_at IS NULL)) ORDER BY e.id LIMIT ?",
                UUID.class, after, after, Timestamp.from(now.minus(timeout)), batchSize);
        cursor = ids.size() < batchSize ? null : ids.getLast();
        int expired = 0;
        for (UUID id : ids) {
            if (Boolean.TRUE.equals(transactions.execute(tx -> expire(id, now)))) expired++;
        }
        return expired;
    }

    private boolean expire(UUID execution, Instant now) {
        var rows = jdbc.queryForList("SELECT e.id,e.job_id,e.parent_log_id,e.started_at,e.meta_json::text "
                + "FROM job_execution_histories e WHERE e.id=? AND e.status IN ('PENDING','RUNNING') "
                + "FOR UPDATE SKIP LOCKED", execution);
        if (rows.isEmpty()) return false;
        // Recheck publication under the same lock used by status/registration completion.
        var published = jdbc.queryForObject("SELECT CASE WHEN count(*)>0 AND bool_and(status='PUBLISHED' "
                + "AND published_at IS NOT NULL) THEN min(published_at) ELSE NULL END "
                + "FROM scheduler_outbox_messages WHERE execution_id=?", Timestamp.class, execution);
        if (published == null || published.toInstant().isAfter(now.minus(timeout))) return false;
        var row = rows.getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = json.readValue(row.get("meta_json").toString(), Map.class);
        var workType = WorkType.valueOf(meta.get("workType").toString());
        var workKey = meta.get("workKey").toString();
        jdbc.update("INSERT INTO output_attempt_timeouts(execution_id,published_at,timed_out_at) "
                + "VALUES (?,?,?) ON CONFLICT DO NOTHING", execution, published, Timestamp.from(now));
        var started = row.get("started_at") instanceof Timestamp stamp ? stamp.toInstant() : published.toInstant();
        jobs.applyStatus(new JobStatusMessage(row.get("job_id").toString(), execution.toString(),
                row.get("parent_log_id").toString(), workType, workKey, "FAILED",
                Map.of("timeoutReason", "EXECUTION_TIMEOUT", "outputWriteOwnership", "UNCERTAIN"),
                null, started, now, Math.max(0L, Duration.between(started, now).toMillis()),
                "EXECUTION_TIMEOUT: published deadline exceeded; output write ownership is uncertain", 0));
        return true;
    }
}
