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
            log.info("JobStatusConsumer received topic={} partition={} offset={} key={} timestamp={} payload={}",
                    record.topic(), record.partition(), record.offset(), record.key(), record.timestamp(),
                    record.value());
            JobStatusMessage response = jsonMapper.readValue(record.value(), JobStatusMessage.class);
            log.info(
                    "JobStatusConsumer parsed status executionId={} parentExecutionId={} symbolKey={} status={} recordsProcessed={} durationMs={} metaKeys={}",
                    response.executionId(), response.parentExecutionId(), response.symbolKey(), response.status(),
                    response.recordsProcessed(), response.durationMs(),
                    response.metaJson() == null ? null : response.metaJson().keySet());
            jobService.applyStatus(response);
            log.info("JobStatusConsumer processed topic={} partition={} offset={} key={} executionId={}",
                    record.topic(), record.partition(), record.offset(), record.key(), response.executionId());
        } catch (Exception e) {
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
            publishMessageProcessingFailed(record, e);
            log.error(
                    "Failed to process stock-sync-status message topic={} partition={} offset={} key={} rootCauseClass={} rootCauseMessage={} payload={}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(), rootCause.getClass().getName(),
                    rootCause.getMessage(), record.value(), e.getMessage(), e);
            throw new RuntimeException("Failed to process stock-sync-status message", e);
        }
    }
}
