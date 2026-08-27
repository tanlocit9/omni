package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SignalJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

@ExtendWith(MockitoExtension.class)
class SyncSignalsJobProducerTest {

    @Mock
    private JobService jobService;

    @Mock
    private KafkaPublisher kafkaPublisher;

    @Mock
    private SymbolRepository symbolRepository;

    @Mock
    private ManifestReader manifestReader;

    @Test
    void dispatchesOnlySymbolsWithExactReadyIndicatorPartitions() {
        SyncSignalsJobProducer producer = new SyncSignalsJobProducer(
                jobService,
                kafkaPublisher,
                symbolRepository,
                manifestReader);
        JobDefinition job = job();
        JobExecutionHistory parent = execution(UUID.randomUUID());
        JobExecutionHistory readyChild = execution(UUID.randomUUID());
        SymbolKeyProjection ready = symbol("HOSE", "HPG");
        SymbolKeyProjection missing = symbol("HNX", "ONE");
        when(symbolRepository.findBySectorCodesAndLevel(null, 2)).thenReturn(List.of(ready, missing));
        when(manifestReader.readManifest(indicatorRef("HOSE", "HPG")))
                .thenReturn(Optional.of(manifest("READY", "HOSE", "HPG")));
        when(manifestReader.readManifest(indicatorRef("HNX", "ONE"))).thenReturn(Optional.empty());
        when(jobService.createChildExecution(eq(parent.getId()),
                eq(WorkIdentity.of(WorkType.SYMBOL, "HOSE-HPG")), any(), any()))
                .thenReturn(readyChild);

        List<KafkaMessage> messages = producer.buildMessages(
                job,
                parent,
                Instant.parse("2026-08-22T00:00:00Z"));

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().key()).isEqualTo("HOSE-HPG");
        SignalJobMessage payload = (SignalJobMessage) messages.getFirst().payload();
        assertThat(payload.symbolKey()).isEqualTo("HOSE-HPG");
        verify(jobService, never()).createChildExecution(eq(parent.getId()),
                eq(WorkIdentity.of(WorkType.SYMBOL, "HNX-ONE")), any(), any());
    }

    @Test
    void defersSymbolWhenIndicatorManifestIsNotReady() {
        SyncSignalsJobProducer producer = new SyncSignalsJobProducer(
                jobService,
                kafkaPublisher,
                symbolRepository,
                manifestReader);
        JobDefinition job = job();
        JobExecutionHistory parent = execution(UUID.randomUUID());
        SymbolKeyProjection symbol = symbol("HNX", "ONE");
        when(symbolRepository.findBySectorCodesAndLevel(null, 2)).thenReturn(List.of(symbol));
        when(manifestReader.readManifest(indicatorRef("HNX", "ONE")))
                .thenReturn(Optional.of(manifest("PROCESSING", "HNX", "ONE")));

        List<KafkaMessage> messages = producer.buildMessages(
                job,
                parent,
                Instant.parse("2026-08-22T00:00:00Z"));

        assertThat(messages).isEmpty();
        verify(jobService, never()).createChildExecution(any(), any(), any(), any());
    }

    private JobDefinition job() {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(DataSource.ANALYZER);
        job.setJobType(JobType.SYNC_SIGNALS);
        job.setConfigJson(Map.of(
                JobDefinitionConfig.CONFIG_KEY_TIMEFRAME, "1d",
                JobDefinitionConfig.CONFIG_KEY_SIGNAL_STRATEGY, "TREND_MOMENTUM_V1"));
        return job;
    }

    private JobExecutionHistory execution(UUID id) {
        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(id);
        return execution;
    }

    private SymbolKeyProjection symbol(String exchange, String code) {
        SymbolKeyProjection symbol = mock(SymbolKeyProjection.class);
        when(symbol.getExchange()).thenReturn(exchange);
        when(symbol.getCode()).thenReturn(code);
        when(symbol.symbolKey()).thenReturn(exchange + "-" + code);
        return symbol;
    }

    private DatasetRef indicatorRef(String exchange, String code) {
        return DatasetRef.of("indicators", Map.of(
                "source", "ad_close",
                "timeframe", "1d",
                "exchange", exchange.toLowerCase(),
                "code", code.toLowerCase()));
    }

    private DatasetManifest manifest(String status, String exchange, String code) {
        return new DatasetManifest(
                1,
                "indicators",
                indicatorRef(exchange, code).getPartition(),
                status,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "indicators/ad_close/1d/" + exchange.toLowerCase() + "/" + code.toLowerCase() + ".parquet",
                1,
                1,
                1,
                0,
                List.of(),
                1,
                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                null,
                null,
                List.of(),
                null,
                "2026-08-22T00:00:00Z");
    }
}
