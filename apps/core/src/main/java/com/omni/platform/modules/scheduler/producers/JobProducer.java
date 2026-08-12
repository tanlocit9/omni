package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
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
         * Job type handled by this producer.
         */
        public abstract JobType getJobType();

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
        public UUID prepareDispatch(
                        JobDefinition job,
                        SchedulerClaim claim,
                        Instant now) {

                log.debug("Preparing job [{}] type [{}] source [{}] at [{}]", job.getId(), job.getJobType(),
                                job.getSource(), now);
                JobExecutionHistory executionLog = jobService.prepareClaimedExecution(job, claim, now);

                List<KafkaMessage> messages = buildMessages(job, executionLog, now);
                log.info("Built {} Kafka message(s) for job [{}] execution [{}] topic [{}]", messages.size(),
                                job.getId(), executionLog.getId(), getTopic());

                if (messages.isEmpty()) {
                        log.warn("No Kafka messages built for job [{}] execution [{}]; marking parent with no children",
                                        job.getId(), executionLog.getId());
                        jobService.markParentWithNoChildren(executionLog, now);
                        jobService.releaseClaim(claim);
                        postPublish(job, now);
                        return executionLog.getId();
                }

                jobService.enqueueDispatch(executionLog, getTopic(), messages, now);
                jobService.releaseClaim(claim);

                postPublish(job, now);
                return executionLog.getId();
        }

}
