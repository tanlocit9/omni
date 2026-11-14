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

    public OutputBackfillService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Baseline(long fence, UUID baselineId, OutputRegistrationEvent.Output output) {}

    public record StorageBaseline(
            long fence,
            long revision,
            String dataset,
            java.util.Map<String, String> partition,
            String storageVersionId,
            String checksum,
            long totalBytes) {}

    /** Pin an operator-validated historical container without inventing logical READY. */
    @Transactional(timeout = 10)
    public long pinStorage(StorageBaseline baseline) {
        OutputRegistrationEvent.require(baseline != null, "Missing storage baseline");
        OutputRegistrationEvent.require(
                lockFence() == baseline.fence(),
                "Writer generation changed during storage baseline");
        requireIdle();
        var keys =
                switch (baseline.dataset() == null ? "" : baseline.dataset()) {
                    case "eod" -> java.util.Set.of("exchange", "code");
                    case "indicators" ->
                            java.util.Set.of("source", "timeframe", "exchange", "code");
                    case "signals" -> java.util.Set.of("strategy", "timeframe", "exchange");
                    default ->
                            throw new IllegalArgumentException(
                                    "Unsupported storage baseline dataset");
                };
        String version = baseline.storageVersionId();
        OutputRegistrationEvent.require(
                baseline.partition() != null
                        && baseline.partition().keySet().equals(keys)
                        && baseline.partition().values().stream()
                                .allMatch(v -> v != null && v.matches("[a-z0-9][a-z0-9._-]*"))
                        && version != null
                        && !version.isEmpty()
                        && version.length() <= 1024
                        && !version.equals("null")
                        && version.chars().allMatch(c -> c >= 33 && c <= 126)
                        && baseline.checksum() != null
                        && baseline.checksum().matches("sha256:[0-9a-f]{64}")
                        && baseline.totalBytes() > 0,
                "Invalid storage baseline evidence");
        long revision =
                jdbc.queryForObject(
                        "SELECT revision FROM output_ready_revision WHERE id=1 FOR UPDATE",
                        Long.class);
        OutputRegistrationEvent.require(
                revision == baseline.revision(),
                "READY changed during storage baseline validation");
        String partition = json.writeValueAsString(baseline.partition());
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(? || ':' || (?::jsonb)::text,1))",
                baseline.dataset(),
                partition);
        OutputRegistrationEvent.require(
                !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM output_registration_outputs o JOIN output_registrations r USING(registration_id) "
                                        + "JOIN output_completion_contracts c ON c.execution_id=r.execution_id JOIN job_execution_histories e ON e.id=c.execution_id "
                                        + "WHERE o.dataset=? AND o.partition_values=?::jsonb AND e.status='SUCCESS' AND NOT EXISTS("
                                        + "SELECT 1 FROM output_ready_pointers p WHERE p.dataset=o.dataset AND p.partition_values=o.partition_values AND p.generation>=c.generation))",
                                Boolean.class,
                                baseline.dataset(),
                                partition)),
                "SUCCESS output promotion is incomplete");
        var existing =
                jdbc.queryForList(
                        "SELECT baseline_version_id,baseline_checksum,baseline_total_bytes FROM output_storage_heads WHERE dataset=? AND partition_values=?::jsonb",
                        baseline.dataset(),
                        partition);
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            OutputRegistrationEvent.require(
                    version.equals(row.get("baseline_version_id"))
                            && baseline.checksum().equals(row.get("baseline_checksum"))
                            && baseline.totalBytes()
                                    == ((Number) row.get("baseline_total_bytes")).longValue(),
                    "Storage baseline is immutable");
            return revision;
        }
        long generation =
                jdbc.queryForObject(
                        "SELECT coalesce(max(generation),0) FROM output_completion_contracts",
                        Long.class);
        jdbc.update(
                "INSERT INTO output_storage_heads(dataset,partition_values,storage_version_id,generation,baseline_version_id,baseline_generation,baseline_checksum,baseline_total_bytes) "
                        + "VALUES (?,?::jsonb,?,?,?,?,?,?)",
                baseline.dataset(),
                partition,
                version,
                generation,
                version,
                generation,
                baseline.checksum(),
                baseline.totalBytes());
        jdbc.update("UPDATE output_ready_revision SET revision=revision+1 WHERE id=1");
        return revision + 1;
    }

    @Transactional(timeout = 10)
    public long begin() {
        long fence = lockFence();
        requireIdle();
        return fence;
    }

    @Transactional(timeout = 10)
    public void commit(Baseline baseline) {
        OutputRegistrationEvent.require(
                baseline != null && baseline.baselineId() != null && baseline.output() != null,
                "Incomplete baseline");
        OutputRegistrationEvent.require(
                lockFence() == baseline.fence(), "Writer generation changed during backfill");
        requireIdle();
        var output = baseline.output();
        OutputRegistrationEvent.require(
                java.util.List.of("eod", "indicators", "signals").contains(output.dataset()),
                "Unsupported baseline dataset");
        var keys =
                switch (output.dataset()) {
                    case "eod" -> java.util.Set.of("exchange", "code");
                    case "indicators" ->
                            java.util.Set.of("source", "timeframe", "exchange", "code");
                    default -> java.util.Set.of("strategy", "timeframe", "exchange");
                };
        OutputRegistrationEvent.require(
                output.partition().keySet().equals(keys) && output.schemaVersion() == 1,
                "Unsupported baseline partition/schema");
        OutputRegistrationEvent.require(
                output.workScope() == null, "Historical baseline cannot invent work readiness");
        String dateColumn = output.dataset().equals("signals") ? "signal_date" : "date";
        OutputRegistrationEvent.require(
                output.columns().stream().anyMatch(c -> c.name().equals(dateColumn)),
                "Baseline has no date column");
        String partition = json.writeValueAsString(output.partition());
        String payload = json.writeValueAsString(output);
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 1))",
                output.dataset() + ":" + partition);
        // Registration always wins. Reconciliation cannot replace live post-cutover evidence.
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM output_ready_pointers "
                                + "WHERE dataset=? AND partition_values=?::jsonb)",
                        Boolean.class,
                        output.dataset(),
                        partition))) return;
        jdbc.update(
                "INSERT INTO output_ready_baselines(id,dataset,partition_values,payload) VALUES (?,?,?::jsonb,?::jsonb) "
                        + "ON CONFLICT DO NOTHING",
                baseline.baselineId(),
                output.dataset(),
                partition,
                payload);
        OutputRegistrationEvent.require(
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM output_ready_baselines "
                                        + "WHERE id=? AND dataset=? AND partition_values=?::jsonb AND payload=?::jsonb)",
                                Boolean.class,
                                baseline.baselineId(),
                                output.dataset(),
                                partition,
                                payload)),
                "Conflicting baseline identity");
        jdbc.update(
                "INSERT INTO output_baseline_pointers(dataset,partition_values,baseline_id) VALUES (?,?::jsonb,?) "
                        + "ON CONFLICT(dataset,partition_values) DO UPDATE SET baseline_id=excluded.baseline_id",
                output.dataset(),
                partition,
                baseline.baselineId());
        jdbc.update("UPDATE output_ready_revision SET revision=revision+1 WHERE id=1");
    }

    private long lockFence() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(21410,22)");
        return jdbc.queryForObject(
                "SELECT last_value + CASE WHEN is_called THEN 1 ELSE 0 END FROM output_backfill_generation",
                Long.class);
    }

    private void requireIdle() {
        boolean active =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM job_execution_histories e "
                                        + "JOIN job_definitions j ON j.id=e.job_id WHERE e.parent_log_id IS NOT NULL "
                                        + "AND e.status IN ('PENDING','RUNNING') AND j.job_type IN "
                                        + "('SYNC_STOCK_PRICE','SYNC_INDICATORS','SYNC_SIGNALS','EVALUATE_SIGNALS'))",
                                Boolean.class));
        boolean uncertain =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM output_attempt_timeouts)",
                                Boolean.class));
        OutputRegistrationEvent.require(
                !active && !uncertain,
                "Backfill requires drained writers and resolved timeout ownership; no automatic recovery");
    }
}
