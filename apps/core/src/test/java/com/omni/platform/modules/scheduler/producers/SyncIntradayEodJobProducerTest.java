package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.IntradayEodJobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

@ExtendWith(MockitoExtension.class)
class SyncIntradayEodJobProducerTest {

    @Mock private JobService jobService;
    @Mock private KafkaPublisher kafkaPublisher;
    @Mock private SymbolRepository symbolRepository;

    @Test
    void dispatchesActiveSymbolsForAllConfiguredVietnamExchanges() {
        SyncIntradayEodJobProducer producer = producer();
        JobDefinition job = job(Map.of(
                JobDefinitionConfig.CONFIG_KEY_EXCHANGES,
                List.of(" hose ", "HNX", "upcom", "HOSE")));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        SymbolKeyProjection hose = symbol("HOSE", "HPG");
        SymbolKeyProjection hnx = symbol("HNX", "SHS");
        SymbolKeyProjection upcom = symbol("UPCOM", "ACV");
        when(symbolRepository.findAllActiveSymbolKeysByExchange("HOSE")).thenReturn(List.of(hose));
        when(symbolRepository.findAllActiveSymbolKeysByExchange("HNX")).thenReturn(List.of(hnx));
        when(symbolRepository.findAllActiveSymbolKeysByExchange("UPCOM")).thenReturn(List.of(upcom));
        stubChild(parent, "HOSE-HPG");
        stubChild(parent, "HNX-SHS");
        stubChild(parent, "UPCOM-ACV");

        List<KafkaMessage> messages = producer.buildMessages(
                job, parent, Instant.parse("2026-09-11T08:16:00Z"));

        assertThat(messages).extracting(KafkaMessage::key)
                .containsExactly("HOSE-HPG:2026-09-11", "HNX-SHS:2026-09-11", "UPCOM-ACV:2026-09-11");
        assertThat(messages).allSatisfy(message -> {
            IntradayEodJobMessage payload = (IntradayEodJobMessage) message.payload();
            assertThat(payload.exchange()).isIn("HOSE", "HNX", "UPCOM");
            assertThat(payload.tradingDate()).isEqualTo(LocalDate.parse("2026-09-11"));
            assertThat(payload.provider()).isEqualTo("VCI");
            assertThat(payload.metadata()).containsEntry(
                    JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of(payload.exchange()));
        });
    }

    @Test
    void defaultsToSharedVietnamExchangesWhenConfigurationIsMissing() {
        SyncIntradayEodJobProducer producer = producer();
        JobDefinition job = job(null);
        JobExecutionHistory parent = execution(UUID.randomUUID());
        for (String exchange : JobDefinitionConfig.VIETNAM_EXCHANGES) {
            when(symbolRepository.findAllActiveSymbolKeysByExchange(exchange)).thenReturn(List.of());
        }

        assertThat(producer.buildMessages(
                job, parent, Instant.parse("2026-09-13T08:16:00Z"))).isEmpty();

        for (String exchange : JobDefinitionConfig.VIETNAM_EXCHANGES) {
            verify(symbolRepository).findAllActiveSymbolKeysByExchange(exchange);
        }
    }

    @Test
    void usesProjectionExchangeAndLatestCompletedWeekday() {
        SyncIntradayEodJobProducer producer = producer();
        JobDefinition job = job(Map.of(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of("hnx")));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        SymbolKeyProjection symbol = symbol("HNX", "SHS");
        when(symbolRepository.findAllActiveSymbolKeysByExchange("HNX")).thenReturn(List.of(symbol));
        stubChild(parent, "HNX-SHS");

        IntradayEodJobMessage payload = (IntradayEodJobMessage) producer.buildMessages(
                job, parent, Instant.parse("2026-09-13T08:16:00Z"))
                .getFirst().payload();

        assertThat(payload.exchange()).isEqualTo("HNX");
        assertThat(payload.tradingDate()).isEqualTo(LocalDate.parse("2026-09-11"));
    }

    @Test
    void manualSingleDateBackfillUsesRequestedHistoricalDate() {
        SyncIntradayEodJobProducer producer = producer();
        JobDefinition job = job(Map.of(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of("HOSE")));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        parent.setMetaJson(Map.of("metadataTarget", Map.of("tradingDate", "2026-09-07")));
        SymbolKeyProjection symbol = symbol("HOSE", "HPG");
        when(symbolRepository.findAllActiveSymbolKeysByExchange("HOSE")).thenReturn(List.of(symbol));
        stubChild(parent, "HOSE-HPG");

        List<KafkaMessage> messages = producer.buildMessages(
                job, parent, Instant.parse("2026-09-11T08:16:00Z"));

        assertThat(messages).hasSize(1);
        IntradayEodJobMessage payload = (IntradayEodJobMessage) messages.getFirst().payload();
        assertThat(payload.tradingDate()).isEqualTo(LocalDate.parse("2026-09-07"));
        assertThat(messages.getFirst().key()).isEqualTo("HOSE-HPG:2026-09-07");
    }

    @Test
    void manualRangeBackfillSkipsWeekendAndFansOutSamePipeline() {
        SyncIntradayEodJobProducer producer = producer();
        JobDefinition job = job(Map.of(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of("HOSE")));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        parent.setMetaJson(Map.of("metadataTarget", Map.of("startDate", "2026-09-04", "endDate", "2026-09-07")));
        SymbolKeyProjection symbol = symbol("HOSE", "HPG");
        when(symbolRepository.findAllActiveSymbolKeysByExchange("HOSE")).thenReturn(List.of(symbol));
        stubChild(parent, "HOSE-HPG");

        List<KafkaMessage> messages = producer.buildMessages(
                job, parent, Instant.parse("2026-09-11T08:16:00Z"));

        assertThat(messages).extracting(message -> ((IntradayEodJobMessage) message.payload()).tradingDate())
                .containsExactly(LocalDate.parse("2026-09-04"), LocalDate.parse("2026-09-07"));
        assertThat(messages).extracting(KafkaMessage::key)
                .containsExactly("HOSE-HPG:2026-09-04", "HOSE-HPG:2026-09-07");
    }

    private SyncIntradayEodJobProducer producer() {
        return new SyncIntradayEodJobProducer(jobService, kafkaPublisher, symbolRepository);
    }

    private JobDefinition job(Map<String, Object> config) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(DataSource.VCI);
        job.setJobType(JobType.SYNC_INTRADAY_EOD);
        job.setConfigJson(config);
        return job;
    }

    private JobExecutionHistory execution(UUID id) {
        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(id);
        return execution;
    }

    private SymbolKeyProjection symbol(String exchange, String code) {
        SymbolKeyProjection symbol = mock(SymbolKeyProjection.class);
        lenient().when(symbol.getExchange()).thenReturn(exchange);
        lenient().when(symbol.getCode()).thenReturn(code);
        when(symbol.symbolKey()).thenReturn(exchange + "-" + code);
        return symbol;
    }

    private void stubChild(JobExecutionHistory parent, String symbolKey) {
        when(jobService.createChildExecution(
                eq(parent.getId()),
                eq(WorkIdentity.of(WorkType.SYMBOL, symbolKey)),
                any(),
                any()))
                .thenReturn(execution(UUID.randomUUID()));
    }
}
