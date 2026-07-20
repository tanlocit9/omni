package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.List;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class JobProducer {

        protected final JobService jobService;
        protected final KafkaPublisher kafkaPublisher;

        /**
         * Kafka topic used by this producer.
         */
        protected abstract String getTopic();

        /**
         * Build Kafka messages for the given job.
         */
        protected abstract List<KafkaMessage> buildMessages(
                        JobDefinition job,
                        JobExecutionHistory log,
                        Instant timestamps);

        /**
         * Optional hook executed after publishing.
         */
        protected void postPublish(
                        JobDefinition job, Instant timestamps) {
        }

        /**
         * Template method.
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void publish(
                        JobDefinition job,
                        Instant now) {

                JobExecutionHistory log = jobService.prepareForExecution(job, now);

                List<KafkaMessage> messages = buildMessages(job, log, now);

                if (messages.isEmpty()) {
                        jobService.markParentWithNoChildren(log, now);
                        postPublish(job, now);
                        return;
                }

                publishMessages(messages);

                postPublish(job, now);
        }

        private void publishMessages(
                        List<KafkaMessage> messages) {

                messages.forEach(message -> kafkaPublisher.publish(
                                getTopic(),
                                message.key(),
                                message.payload()));
        }

}