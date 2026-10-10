package com.omni.platform.modules.scheduler.consumers;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.AbstractConsumer;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class JobStatusConsumer extends AbstractConsumer {

    private final JobService jobService;
    private final JsonMapper jsonMapper;

    public JobStatusConsumer(
            ApplicationEventPublisher eventPublisher,
            JobService jobService,
            JsonMapper jsonMapper) {
        super(eventPublisher);
        this.jobService = jobService;
        this.jsonMapper = jsonMapper;
    }

    @Value("${kafka.topics.topic-sync-job-status}")
    private String jobStatusTopic;

    @Override
    protected String topicName() {
        return jobStatusTopic;
    }

    @KafkaListener(topics = "${kafka.topics.topic-sync-job-status}", groupId = "${spring.kafka.consumer.group-id}")
    public void handleSyncStatus(ConsumerRecord<String, String> record) {
        try {
            log.info("[JOB_STATUS_TRACE] JobStatusConsumer received topic={} partition={} offset={} key={} timestamp={}",
                    record.topic(), record.partition(), record.offset(), record.key(), record.timestamp());
            JobStatusMessage response = jsonMapper.readValue(record.value(), JobStatusMessage.class);
            log.info(
                    "[JOB_STATUS_TRACE] JobStatusConsumer parsed status executionId={} parentExecutionId={} workType={} workKey={} status={} recordsProcessed={} durationMs={} metaKeys={}",
                    response.executionId(), response.parentExecutionId(), response.workType(), response.workKey(), response.status(),
                    response.recordsProcessed(), response.durationMs(),
                    response.metaJson() == null ? null : response.metaJson().keySet());
            log.info(
                    "[JOB_STATUS_TRACE] JobStatusConsumer applying topic={} partition={} offset={} key={} executionId={} parentExecutionId={} workType={} workKey={} status={}",
                    record.topic(), record.partition(), record.offset(), record.key(), response.executionId(),
                    response.parentExecutionId(), response.workType(), response.workKey(), response.status());
            jobService.applyStatus(response);
            log.info(
                    "[JOB_STATUS_TRACE] JobStatusConsumer applied topic={} partition={} offset={} key={} executionId={} parentExecutionId={} workType={} workKey={} status={}",
                    record.topic(), record.partition(), record.offset(), record.key(), response.executionId(),
                    response.parentExecutionId(), response.workType(), response.workKey(), response.status());
        } catch (Exception e) {
            // TODO(TD-010): Define bounded poison-record quarantine/recovery with offset safety.
            // Ref: docs/technical-debt/010-kafka-poison-record-and-dead-letter-policy.md
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
            publishMessageProcessingFailed(record, e);
            log.error(
                    "[JOB_STATUS_TRACE] Failed to process job-status message topic={} partition={} offset={} key={} rootCauseClass={} rootCauseMessage={}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), rootCause.getClass().getName(),
                    rootCause.getMessage(), e.getMessage(), e);
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }
}
