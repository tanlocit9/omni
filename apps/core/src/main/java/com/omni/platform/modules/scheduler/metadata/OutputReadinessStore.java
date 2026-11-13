package com.omni.platform.modules.scheduler.metadata;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** READY-last for enrolled scoped writers only; shadow evidence never promotes. */
@Service
public class OutputReadinessStore {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    @org.springframework.beans.factory.annotation.Autowired private OutputStorageHeads storageHeads;

    public OutputReadinessStore(
            JdbcTemplate jdbc,
            @Value("${app.output-registration.ready-promotion-enabled:false}") boolean enabled,
            @Value("${app.output-registration.enrollment-enabled:false}") boolean enrollment) {
        if (enabled && !enrollment)
            throw new IllegalArgumentException("READY promotion requires enrollment");
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void promote(UUID execution) {
        if (!enabled
                && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM atomic_work_requests r "
                                        + "JOIN atomic_work_request_members m ON m.request_id=r.id "
                                        + "JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                        + "WHERE r.mode='BACKFILL' AND a.execution_id=?)",
                                Boolean.class,
                                execution))) return;
        var outputs =
                jdbc.queryForList(
                        "SELECT r.atomic_work_id,o.dataset,o.partition_values::text AS partition,o.registration_id,o.output_index,c.generation,o.payload->>'storageVersionId' AS storage_version_id "
                                + "FROM output_registration_outputs o JOIN output_registrations r USING(registration_id) "
                                + "JOIN output_completion_contracts c ON c.execution_id=r.execution_id AND c.atomic_attempt_id=r.atomic_attempt_id "
                                + "JOIN output_completion_statuses s ON s.execution_id=c.execution_id "
                                + "JOIN job_execution_histories e ON e.id=c.execution_id "
                                + "WHERE e.id=? AND e.status='SUCCESS' AND NOT EXISTS "
                                + "(SELECT 1 FROM output_attempt_timeouts t WHERE t.execution_id=e.id) "
                                + "ORDER BY o.dataset,o.partition_values::text",
                        execution);
        for (var output : outputs) {
            // Shared history writers and competing attempts serialize the pointer transaction.
            jdbc.queryForList(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 1))",
                    output.get("dataset") + ":" + output.get("partition"));
            if (storageHeads != null
                    && !storageHeads.promote(
                            execution,
                            output.get("dataset").toString(),
                            output.get("partition").toString(),
                            ((Number) output.get("generation")).longValue(),
                            (String) output.get("storage_version_id"))) continue;
            int rowsChanged =
                    jdbc.update(
                            "INSERT INTO output_ready_rows(dataset,partition_values,row_key,registration_id,output_index,generation) "
                                    + "SELECT o.dataset,o.partition_values,k,o.registration_id,o.output_index,? "
                                    + "FROM output_registration_outputs o CROSS JOIN LATERAL jsonb_array_elements(o.payload->'workScope'->'rowKeys') k "
                                    + "WHERE o.registration_id=? AND o.output_index=? "
                                    + "ON CONFLICT(dataset,partition_values,row_key) DO UPDATE SET registration_id=excluded.registration_id,"
                                    + "output_index=excluded.output_index,generation=excluded.generation WHERE output_ready_rows.generation<excluded.generation",
                            output.get("generation"),
                            output.get("registration_id"),
                            output.get("output_index"));
            int worksChanged =
                    jdbc.update(
                            "INSERT INTO output_work_ready(atomic_work_id,dataset,partition_values,registration_id,output_index,generation) "
                                    + "VALUES (?,?,?::jsonb,?,?,?) ON CONFLICT(atomic_work_id,dataset,partition_values) DO UPDATE SET "
                                    + "registration_id=excluded.registration_id,output_index=excluded.output_index,"
                                    + "generation=excluded.generation,promoted_at=CURRENT_TIMESTAMP "
                                    + "WHERE output_work_ready.generation<excluded.generation",
                            output.get("atomic_work_id"),
                            output.get("dataset"),
                            output.get("partition"),
                            output.get("registration_id"),
                            output.get("output_index"),
                            output.get("generation"));
            int changed =
                    jdbc.update(
                            "INSERT INTO output_ready_pointers(dataset,partition_values,registration_id,output_index,generation) "
                                    + "VALUES (?,?::jsonb,?,?,?) ON CONFLICT(dataset,partition_values) DO UPDATE SET "
                                    + "registration_id=excluded.registration_id,output_index=excluded.output_index,"
                                    + "generation=excluded.generation,promoted_at=CURRENT_TIMESTAMP "
                                    + "WHERE output_ready_pointers.generation<excluded.generation",
                            output.get("dataset"),
                            output.get("partition"),
                            output.get("registration_id"),
                            output.get("output_index"),
                            output.get("generation"));
            jdbc.update(
                    "INSERT INTO output_work_ready_history(registration_id,output_index) "
                            + "SELECT w.registration_id,w.output_index FROM output_work_ready w "
                            + "JOIN output_work_ready_catalog p USING(atomic_work_id,dataset,partition_values) "
                            + "JOIN output_completion_contracts c ON c.execution_id=p.execution_id AND c.atomic_attempt_id=p.atomic_attempt_id "
                            + "WHERE w.registration_id=? AND w.output_index=? AND c.storage_policy='VERSIONED' "
                            + "AND p.payload->>'storageVersionId' IS NOT NULL ON CONFLICT DO NOTHING",
                    output.get("registration_id"),
                    output.get("output_index"));
            if (changed + rowsChanged + worksChanged > 0)
                jdbc.update("UPDATE output_ready_revision SET revision=revision+1 WHERE id=1");
        }
    }
}
