package com.omni.platform.modules.scheduler.metadata;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.omni.platform.modules.scheduler.entities.JobDefinition;

/** Opt-in schedule override; persisted definition identity and accepted work stay unchanged. */
@Component
public class OutputReconciliationPolicy {
    private final boolean enabled;
    public OutputReconciliationPolicy(
            @Value("${app.output-registration.reconciliation-schedule-enabled:false}") boolean enabled,
            @Value("${app.output-registration.reader-enabled:false}") boolean postgres) {
        if (enabled && !postgres) throw new IllegalArgumentException("12-hour reconciliation requires PostgreSQL reader cutover");
        this.enabled=enabled;
    }
    public String cron(JobDefinition job) {
        return enabled && job.getJobType()==JobDefinition.JobType.SYNC_METADATA ? "0 0 */12 * * *" : job.getCronExpr();
    }
}
