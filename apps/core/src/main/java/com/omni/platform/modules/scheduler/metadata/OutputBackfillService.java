package com.omni.platform.modules.scheduler.metadata;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Fenced baseline commit. Unknown provenance stays unknown; never enroll historical work. */
@Service
public class OutputBackfillService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    public OutputBackfillService(JdbcTemplate jdbc, JsonMapper json) { this.jdbc=jdbc; this.json=json; }

    public record Baseline(long fence, UUID baselineId, OutputRegistrationEvent.Output output) {}

    @Transactional(timeout=10)
    public long begin() {
        long fence = lockFence();
        requireIdle();
        return fence;
    }

    @Transactional(timeout=10)
    public void commit(Baseline baseline) {
        OutputRegistrationEvent.require(baseline != null && baseline.baselineId()!=null && baseline.output()!=null,
                "Incomplete baseline");
        OutputRegistrationEvent.require(lockFence() == baseline.fence(), "Writer generation changed during backfill");
        requireIdle();
        var output=baseline.output();
        OutputRegistrationEvent.require(java.util.List.of("eod","indicators","signals").contains(output.dataset()),
                "Unsupported baseline dataset");
        var keys = switch(output.dataset()) {
            case "eod" -> java.util.Set.of("exchange","code");
            case "indicators" -> java.util.Set.of("source","timeframe","exchange","code");
            default -> java.util.Set.of("strategy","timeframe","exchange");
        };
        OutputRegistrationEvent.require(output.partition().keySet().equals(keys) && output.schemaVersion()==1,
                "Unsupported baseline partition/schema");
        String dateColumn=output.dataset().equals("signals") ? "signal_date" : "date";
        OutputRegistrationEvent.require(output.columns().stream().anyMatch(c -> c.name().equals(dateColumn)),
                "Baseline has no date column");
        String partition=json.writeValueAsString(output.partition());
        String payload=json.writeValueAsString(output);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", output.dataset()+":"+partition);
        // Registration always wins. Reconciliation cannot replace live post-cutover evidence.
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM output_ready_pointers "
                + "WHERE dataset=? AND partition_values=?::jsonb)", Boolean.class, output.dataset(),partition))) return;
        jdbc.update("INSERT INTO output_ready_baselines(id,dataset,partition_values,payload) VALUES (?,?,?::jsonb,?::jsonb) "
                + "ON CONFLICT DO NOTHING", baseline.baselineId(),output.dataset(),partition,payload);
        OutputRegistrationEvent.require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM output_ready_baselines "
                + "WHERE id=? AND dataset=? AND partition_values=?::jsonb AND payload=?::jsonb)", Boolean.class,
                baseline.baselineId(),output.dataset(),partition,payload)), "Conflicting baseline identity");
        jdbc.update("INSERT INTO output_baseline_pointers(dataset,partition_values,baseline_id) VALUES (?,?::jsonb,?) "
                + "ON CONFLICT(dataset,partition_values) DO UPDATE SET baseline_id=excluded.baseline_id",
                output.dataset(),partition,baseline.baselineId());
        jdbc.update("UPDATE output_ready_revision SET revision=revision+1 WHERE id=1");
    }

    private long lockFence() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(21410,22)");
        return jdbc.queryForObject("SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END FROM output_backfill_generation",Long.class);
    }

    private void requireIdle() {
        boolean active=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM job_execution_histories e "
                + "JOIN job_definitions j ON j.id=e.job_id WHERE e.parent_log_id IS NOT NULL "
                + "AND e.status IN ('PENDING','RUNNING') AND j.job_type IN "
                + "('SYNC_STOCK_PRICE','SYNC_INDICATORS','SYNC_SIGNALS','EVALUATE_SIGNALS'))",Boolean.class));
        boolean uncertain=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM output_attempt_timeouts)",Boolean.class));
        OutputRegistrationEvent.require(!active && !uncertain,
                "Backfill requires drained writers and resolved timeout ownership; no automatic recovery");
    }
}
