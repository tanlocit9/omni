package com.omni.platform.modules.scheduler.metadata;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Serialize enrolled mutations of one logical output; timeout never releases ownership. */
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
        if (!enabled) return false;
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
                                + "(SELECT 1 FROM output_attempt_timeouts t WHERE t.execution_id=prior.execution_id)))",
                        Boolean.class,
                        execution));
    }
}
