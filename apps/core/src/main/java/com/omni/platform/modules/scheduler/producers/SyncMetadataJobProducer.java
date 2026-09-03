package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SyncMetadataJobMessage;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SyncMetadataJobProducer extends JobProducer {
    @Value("${kafka.topics.topic-sync-metadata}")
    private String topic;

    public SyncMetadataJobProducer(JobService jobService, KafkaPublisher kafkaPublisher) {
        super(jobService, kafkaPublisher);
    }

    @Override
    public JobType getJobType() {
        return JobType.SYNC_METADATA;
    }

    @Override
    protected String getTopic() {
        return topic;
    }

    @Override
    protected List<KafkaMessage> buildMessages(
            JobDefinition job,
            JobExecutionHistory jobExecutionHistory,
            Instant timestamps) {
        Map<String, Object> config = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        Map<String, Object> target = null;
        if (jobExecutionHistory.getMetaJson() != null
                && jobExecutionHistory.getMetaJson().get("metadataTarget") instanceof Map<?, ?> raw
                && !raw.isEmpty()) {
            target = raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
        }

        SyncMetadataJobMessage message = new SyncMetadataJobMessage(
                job.getId(),
                jobExecutionHistory.getId(),
                jobExecutionHistory.getParentLogId(),
                job.getSource().toString(),
                WorkType.GLOBAL,
                job.getJobType().name(),
                target,
                config);
        return List.of(new KafkaMessage("metadata", message));
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published sync-metadata job [{}] for source [{}]", job.getId(), job.getSource());
    }
}
