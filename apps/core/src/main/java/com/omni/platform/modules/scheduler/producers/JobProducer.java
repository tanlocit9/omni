package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.SyncJob;
import com.omni.platform.modules.scheduler.entities.SyncJobLog;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.services.SyncJobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class JobProducer {

    protected final SyncJobService syncJobService;
    protected final KafkaPublisher kafkaPublisher;

    /**
     * Kafka topic used by this producer.
     */
    protected abstract String getTopic();

    /**
     * Build Kafka messages for the given job.
     */
    protected abstract List<KafkaMessage> buildMessages(
            SyncJob job,
            SyncJobLog log, Instant timestamps);

    /**
     * Optional hook executed after publishing.
     */
    protected void postPublish(
            SyncJob job, Instant timestamps) {
    }

    /**
     * Template method.
     */
    @Transactional
    public void publish(
            SyncJob job,
            Instant now) {

        SyncJobLog log = syncJobService.prepareForExecution(
                job,
                now);

        List<KafkaMessage> messages = buildMessages(job, log, now);

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
