package com.omni.platform.modules.scheduler.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.notifications.templates.JobNotificationTemplate;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;

class JobNotificationPolicyRegistryTest {

    @Test
    void resolvesCustomPolicyByJobType() {
        DefaultJobNotificationPolicy defaultPolicy = new DefaultJobNotificationPolicy(new JobNotificationTemplate());
        TestPolicy customPolicy = new TestPolicy(JobType.SECTOR_TRANSITION_ANALYZE, "custom-event");
        JobNotificationPolicyRegistry registry = new JobNotificationPolicyRegistry(List.of(customPolicy), defaultPolicy);

        Optional<Object> event = registry.buildNotification(context(JobType.SECTOR_TRANSITION_ANALYZE));

        assertThat(event).contains("custom-event");
    }

    @Test
    void fallsBackToDefaultPolicyWhenNoCustomPolicyExists() {
        DefaultJobNotificationPolicy defaultPolicy = new DefaultJobNotificationPolicy(new JobNotificationTemplate());
        JobNotificationPolicyRegistry registry = new JobNotificationPolicyRegistry(List.of(), defaultPolicy);

        Optional<Object> event = registry.buildNotification(context(JobType.SYNC_STOCK_PRICE));

        assertThat(event).isPresent();
        assertThat(event.get()).isInstanceOf(OperationalNotificationEvent.class);
    }

    @Test
    void rejectsDuplicatePolicyRegistration() {
        DefaultJobNotificationPolicy defaultPolicy = new DefaultJobNotificationPolicy(new JobNotificationTemplate());
        TestPolicy first = new TestPolicy(JobType.SYNC_SIGNALS, "first");
        TestPolicy second = new TestPolicy(JobType.SYNC_SIGNALS, "second");

        assertThatThrownBy(() -> new JobNotificationPolicyRegistry(List.of(first, second), defaultPolicy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate JobNotificationPolicy registration for jobType SYNC_SIGNALS");
    }

    @Test
    void defaultPolicyBeanIsIgnoredAsRegisteredCustomPolicy() {
        DefaultJobNotificationPolicy defaultPolicy = new DefaultJobNotificationPolicy(new JobNotificationTemplate());
        JobNotificationPolicyRegistry registry = new JobNotificationPolicyRegistry(List.of(defaultPolicy), defaultPolicy);

        Optional<Object> event = registry.buildNotification(context(JobType.SYNC_SYMBOLS));

        assertThat(event).isPresent();
        assertThat(event.get()).isInstanceOf(OperationalNotificationEvent.class);
    }

    private JobNotificationContext context(JobType jobType) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setTitle("Test job");
        job.setJobType(jobType);
        job.setSource(DataSource.VND);

        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(job);
        execution.setStatus(JobStatus.SUCCESS);
        return new JobNotificationContext(execution, List.of(), 0, 0, 0);
    }

    private static final class TestPolicy implements JobNotificationPolicy {
        private final JobType jobType;
        private final Object event;

        private TestPolicy(JobType jobType, Object event) {
            this.jobType = jobType;
            this.event = event;
        }

        @Override
        public JobType getJobType() {
            return jobType;
        }

        @Override
        public Optional<Object> buildNotification(JobNotificationContext context) {
            return Optional.of(event);
        }
    }
}
