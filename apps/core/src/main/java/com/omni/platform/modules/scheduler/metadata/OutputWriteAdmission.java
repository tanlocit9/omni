package com.omni.platform.modules.scheduler.metadata;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Serialize logical output merges; only immutable versions can release timed-out ownership. */
@Service
public class OutputWriteAdmission {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public OutputWriteAdmission(
            JdbcTemplate jdbc,
            @Value("${app.output-registration.enrollment-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean waiting(UUID execution) {
        // A scoped backfill may be enrolled while global enrollment stays disabled.
        // Persisted contracts remain authoritative for already accepted work.
        if (!enabled
                && !Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM output_completion_contracts WHERE execution_id=?)",
                                Boolean.class,
                                execution))) return false;
        // Production enrollment is committed under GraphWorkRequestService's catalog locks,
        // so a lower generation cannot become visible after this committed target.
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM output_completion_contracts target "
                                + "JOIN output_completion_contracts prior ON prior.generation<target.generation "
                                + "JOIN job_execution_histories e ON e.id=prior.execution_id "
                                + "WHERE target.execution_id=? AND EXISTS (SELECT 1 FROM jsonb_array_elements(target.required_outputs) wanted "
                                + "JOIN jsonb_array_elements(prior.required_outputs) owned ON wanted.value=owned.value) "
                                + "AND (e.status IN ('PENDING','RUNNING') OR EXISTS "
                                + "(SELECT 1 FROM output_attempt_timeouts t WHERE t.execution_id=prior.execution_id "
                                + "AND NOT (prior.storage_policy='VERSIONED' AND target.storage_policy='VERSIONED')) "
                                + "OR (e.status='SUCCESS' AND prior.storage_policy='VERSIONED' AND EXISTS ("
                                + "SELECT 1 FROM output_registrations r JOIN output_registration_outputs o USING(registration_id) "
                                + "WHERE r.execution_id=prior.execution_id AND NOT EXISTS(SELECT 1 FROM output_storage_heads h "
                                + "WHERE h.dataset=o.dataset AND h.partition_values=o.partition_values AND h.generation>=prior.generation)))))",
                        Boolean.class,
                        execution));
    }
}
