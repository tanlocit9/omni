package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.SyncJob;
import com.omni.platform.modules.scheduler.entities.SyncJobLog;
import com.omni.platform.modules.scheduler.entities.SyncJobSymbol;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SyncJobMessage;
import com.omni.platform.modules.scheduler.repositories.SyncJobSymbolRepository;
import com.omni.platform.modules.scheduler.services.SyncJobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SyncSymbolsJobProducer extends JobProducer {

    private final SyncJobSymbolRepository jobSymbolRepository;

    @Value("${spring.kafka.topics.sync-job}")
    private String topic;

    public SyncSymbolsJobProducer(
            SyncJobService syncJobService,
            KafkaPublisher kafkaPublisher,
            SyncJobSymbolRepository jobSymbolRepository) {

        super(syncJobService, kafkaPublisher);

        this.jobSymbolRepository = jobSymbolRepository;
    }

    @Override
    protected String getTopic() {
        return topic;
    }

    @Override
    protected List<KafkaMessage> buildMessages(
            SyncJob job,
            SyncJobLog log, Instant timestamps) {

        List<SyncJobSymbol> symbols = jobSymbolRepository.findActiveByJobId(
                job.getId());
        return symbols.stream()
                .filter(symbol -> !isUpToDate(symbol, timestamps))
                .map(symbol -> new KafkaMessage(
                        symbol.getSymbol().getCode(),
                        new SyncJobMessage(
                                job.getId(),
                                log.getId(),
                                symbol.getSymbol().getCode(),
                                symbol.getLastOffset(),
                                timestamps,
                                job.getConfigJson())))
                .toList();
    }

    @Override
    protected void postPublish(
            SyncJob job, Instant now) {

        log.info(
                "Published sync job [{}] for source [{}]",
                job.getId(),
                job.getSource());
    }

    private boolean isUpToDate(SyncJobSymbol symbol, Instant now) {

        Instant lastOffset = symbol.getLastOffset();

        if (lastOffset == null) {
            return false;
        }

        return !lastOffset.isBefore(now);
    }
}
