package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.omni.platform.modules.scheduler.entities.JobDefinition;

class OutputReconciliationPolicyTest {
    @Test
    void overrideRequiresCutoverAndPreservesOtherDefinitions() {
        assertThatThrownBy(() -> new OutputReconciliationPolicy(true, false))
                .isInstanceOf(IllegalArgumentException.class);
        var job = new JobDefinition();
        job.setJobType(JobDefinition.JobType.SYNC_METADATA);
        job.setCronExpr("0 0 20 * * MON-FRI");
        assertThat(new OutputReconciliationPolicy(false, false).cron(job))
                .isEqualTo(job.getCronExpr());
        var policy = new OutputReconciliationPolicy(true, true);
        var cron = org.springframework.scheduling.support.CronExpression.parse(policy.cron(job));
        assertThat(cron.next(java.time.LocalDateTime.parse("2026-11-01T00:00:00")))
                .isEqualTo(java.time.LocalDateTime.parse("2026-11-01T12:00:00"));
        job.setJobType(JobDefinition.JobType.SYNC_STOCK_PRICE);
        assertThat(policy.cron(job)).isEqualTo(job.getCronExpr());
    }
}
