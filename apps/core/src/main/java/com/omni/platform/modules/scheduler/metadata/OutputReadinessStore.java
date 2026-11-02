package com.omni.platform.modules.scheduler.metadata;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** READY-last for enrolled snapshot writers only; shadow evidence never promotes. */
@Service
public class OutputReadinessStore {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public OutputReadinessStore(JdbcTemplate jdbc,
            @Value("${app.output-registration.ready-promotion-enabled:false}") boolean enabled,
            @Value("${app.output-registration.enrollment-enabled:false}") boolean enrollment) {
        if (enabled && !enrollment) throw new IllegalArgumentException("READY promotion requires enrollment");
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void promote(UUID execution) {
        if (!enabled) return;
        var outputs = jdbc.queryForList("SELECT o.dataset,o.partition_values::text AS partition,o.registration_id,o.output_index,c.generation "
                + "FROM output_registration_outputs o JOIN output_registrations r USING(registration_id) "
                + "JOIN output_completion_contracts c ON c.execution_id=r.execution_id AND c.atomic_attempt_id=r.atomic_attempt_id "
                + "JOIN output_completion_statuses s ON s.execution_id=c.execution_id "
                + "JOIN job_execution_histories e ON e.id=c.execution_id "
                + "WHERE e.id=? AND e.status='SUCCESS' AND NOT EXISTS "
                + "(SELECT 1 FROM output_attempt_timeouts t WHERE t.execution_id=e.id) "
                + "ORDER BY o.dataset,o.partition_values::text", execution);
        for (var output : outputs) {
            // Shared history writers and competing attempts serialize the pointer transaction.
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))",
                    output.get("dataset") + ":" + output.get("partition"));
            int changed = jdbc.update("INSERT INTO output_ready_pointers(dataset,partition_values,registration_id,output_index,generation) "
                    + "VALUES (?,?::jsonb,?,?,?) ON CONFLICT(dataset,partition_values) DO UPDATE SET "
                    + "registration_id=excluded.registration_id,output_index=excluded.output_index,"
                    + "generation=excluded.generation,promoted_at=CURRENT_TIMESTAMP "
                    + "WHERE output_ready_pointers.generation<excluded.generation",
                    output.get("dataset"), output.get("partition"), output.get("registration_id"),
                    output.get("output_index"), output.get("generation"));
            if (changed > 0) jdbc.update("UPDATE output_ready_revision SET revision=revision+1 WHERE id=1");
        }
    }

}
