package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

class JobProducerRegistryTest {

    @Test
    void resolvesProducerByJobType() {
        TestJobProducer producer = new TestJobProducer(JobType.SYNC_STOCK_PRICE);
        JobProducerRegistry registry = new JobProducerRegistry(List.of(producer));

        assertThat(registry.getProducer(JobType.SYNC_STOCK_PRICE)).isSameAs(producer);
    }

    @Test
    void rejectsDuplicateProducerRegistration() {
        TestJobProducer first = new TestJobProducer(JobType.SYNC_STOCK_PRICE);
        TestJobProducer second = new TestJobProducer(JobType.SYNC_STOCK_PRICE);

        assertThatThrownBy(() -> new JobProducerRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate JobProducer registration for jobType SYNC_STOCK_PRICE");
    }

    @Test
    void rejectsMissingProducerLookup() {
        JobProducerRegistry registry = new JobProducerRegistry(List.of(new TestJobProducer(JobType.SYNC_SYMBOLS)));

        assertThatThrownBy(() -> registry.getProducer(JobType.SYNC_STOCK_PRICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No JobProducer registered for jobType SYNC_STOCK_PRICE");
    }

    private static final class TestJobProducer extends JobProducer {
        private final JobType jobType;

        private TestJobProducer(JobType jobType) {
            super(mock(JobService.class), mock(KafkaPublisher.class));
            this.jobType = jobType;
        }

        @Override
        public JobType getJobType() {
            return jobType;
        }

        @Override
        protected String getTopic() {
            return "test-topic";
        }

        @Override
        protected List<KafkaMessage> buildMessages(JobDefinition job, JobExecutionHistory log, Instant timestamps) {
            return List.of();
        }
    }
}
