package com.omni.platform.modules.scheduler.producers;

import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProducerContext {

    protected final JobDefinitionRepository jobDefinitionRepository;
    protected final JobExecutionHistoryRepository jobExecutionHistoryRepository;
    protected final KafkaPublisher kafkaPublisher;
}
