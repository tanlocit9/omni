package com.omni.platform.modules.scheduler.producers;

import com.omni.platform.modules.scheduler.repositories.SyncJobLogRepository;
import com.omni.platform.modules.scheduler.repositories.SyncJobRepository;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProducerContext {

    protected final SyncJobRepository syncJobRepository;
    protected final SyncJobLogRepository syncJobLogRepository;
    protected final KafkaPublisher kafkaPublisher;
}