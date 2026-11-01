package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.JobMessage;
import com.omni.platform.modules.scheduler.messaging.SyncMetadataJobMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retained direct producer adapters must keep valid identities and default/filter semantics. */
class ProducerCompatibilityPathsTest {
    private final Instant now = Instant.parse("2026-11-02T05:00:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainedProducersHandleNullDefaultsAndExplicitSelectors(boolean configured) {
        var jobs = mock(JobService.class);
        var publisher = mock(KafkaPublisher.class);
        var symbols = mock(SymbolRepository.class);
        SymbolKeyProjection symbol =
                new SymbolKeyProjection() {
                    public String getCode() {
                        return "HPG";
                    }

                    public String getExchange() {
                        return "HOSE";
                    }
                };
        when(symbols.findBySectorCodesAndLevel(any(), anyInt())).thenReturn(List.of(symbol));
        when(symbols.findDistinctSectorCodesByLevel(any(), anyInt())).thenReturn(List.of("BANKS"));
        when(jobs.createChildExecution(any(), any(), any(), eq(now)))
                .thenAnswer(
                        call -> {
                            var child = new JobExecutionHistory();
                            child.setId(UUID.randomUUID());
                            return child;
                        });
        List<JobProducer> producers =
                List.of(
                        new SyncIndicatorsJobProducer(jobs, publisher, symbols),
                        new SyncStockPriceJobProducer(
                                jobs,
                                publisher,
                                symbols,
                                mock(JobExecutionHistoryRepository.class)),
                        new PrecomputeSymbolFeaturesJobProducer(jobs, publisher, symbols),
                        new SectorRotationBacktestJobProducer(jobs, publisher, symbols),
                        new EvaluateSignalsJobProducer(jobs, publisher));
        for (var producer : producers) {
            var job = definition(producer.getJobType());
            Map<String, Object> config =
                    configured
                            ? Map.of(
                                    "sectorCodes",
                                    List.of("BANKS"),
                                    "sectorLevel",
                                    2,
                                    "exchanges",
                                    List.of("HOSE"))
                            : null;
            job.setConfigJson(config);
            var parent = parent(job);
            var messages = producer.buildMessages(job, parent, now);
            assertThat(messages).isNotEmpty();
            for (var message : messages) {
                var payload = (JobMessage) message.payload();
                assertThat(payload.jobDefinitionId()).isEqualTo(job.getId());
                assertThat(payload.parentExecutionId()).isEqualTo(parent.getId());
                assertThat(payload.executionId()).isNotNull().isNotEqualTo(parent.getId());
                assertThat(payload.workType()).isNotNull();
                assertThat(payload.workKey()).isNotBlank();
            }
            assertThat(job.getConfigJson()).isEqualTo(config);
        }
        verifyNoInteractions(publisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "empty", "wrong-type", "target"})
    void retainedMetadataTargetIsOptionalAndNotInvented(String mode) {
        var jobs = mock(JobService.class);
        var publisher = mock(KafkaPublisher.class);
        var job = definition(JobType.SYNC_METADATA);
        job.setConfigJson(null);
        var parent = parent(job);
        Map<String, Object> target = Map.of("dataset", "eod");
        parent.setMetaJson(
                switch (mode) {
                    case "absent" -> null;
                    case "empty" -> Map.of("metadataTarget", Map.of());
                    case "wrong-type" -> Map.of("metadataTarget", "eod");
                    default -> Map.of("metadataTarget", target);
                });
        var producer = new SyncMetadataJobProducer(jobs, publisher);
        var messages = producer.buildMessages(job, parent, now);
        assertThat(messages).hasSize(1);
        var payload = (SyncMetadataJobMessage) messages.getFirst().payload();
        assertThat(payload.executionId()).isEqualTo(parent.getId());
        assertThat(payload.target()).isEqualTo(mode.equals("target") ? target : null);
        assertThat(payload.metadata()).isEmpty();
        verifyNoInteractions(jobs, publisher);
    }

    private JobDefinition definition(JobType type) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(type);
        job.setSource(type == JobType.SYNC_STOCK_PRICE ? DataSource.VND : DataSource.ANALYZER);
        return job;
    }

    private JobExecutionHistory parent(JobDefinition job) {
        var parent = new JobExecutionHistory();
        parent.setId(UUID.randomUUID());
        parent.setJob(job);
        parent.setMetaJson(null);
        return parent;
    }
}
