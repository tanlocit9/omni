package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.SyncSignalsConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SignalEvaluationJobMessage;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EvaluateSignalsJobProducer extends JobProducer {

    @Value("${kafka.topics.topic-evaluate-signals}")
    private String topic;

    public EvaluateSignalsJobProducer(
            JobService jobService,
            KafkaPublisher kafkaPublisher) {

        super(jobService, kafkaPublisher);
    }

    @Override
    public JobType getJobType() {
        return JobType.EVALUATE_SIGNALS;
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
        Map<String, Object> jobConfig = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        SyncSignalsConfig config = JobConfigMapper.toSignalsConfig(jobConfig);
        String timeframe = config.timeframe();
        String strategy = config.strategy();

        List<String> exchanges = JobConfigMapper.readStringList(
                jobConfig,
                JobDefinitionConfig.CONFIG_KEY_EXCHANGES);
        if (exchanges.isEmpty()) {
            exchanges = JobDefinitionConfig.VIETNAM_EXCHANGES;
        }

        log.info("Evaluating signals for exchanges={} strategy={} timeframe={} jobId={} executionId={}",
                exchanges, strategy, timeframe, job.getId(), jobExecutionHistory.getId());

        return exchanges.stream()
                .map(exchange -> {
                    String normalizedExchange = exchange == null ? "" : exchange.trim().toUpperCase();
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.putAll(jobConfig);
                    metadata.put(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of(normalizedExchange));

                    JobExecutionHistory childJobExecutionHistory = jobService.createChildExecution(
                            jobExecutionHistory.getId(),
                            normalizedExchange,
                            metadata,
                            timestamps);

                    return new KafkaMessage(
                            normalizedExchange,
                            new SignalEvaluationJobMessage(
                                    job.getId(),
                                    childJobExecutionHistory.getId(),
                                    jobExecutionHistory.getId(),
                                    job.getSource().toString(),
                                    normalizedExchange,
                                    timeframe,
                                    strategy,
                                    metadata));
                })
                .toList();
    }

    @Override
    protected void postPublish(JobDefinition job, Instant now) {
        log.info("Published signal evaluation job [{}] for source [{}]", job.getId(), job.getSource());
    }
}
