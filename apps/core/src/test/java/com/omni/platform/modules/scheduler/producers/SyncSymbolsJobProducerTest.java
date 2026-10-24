package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.SyncSymbolsJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

class SyncSymbolsJobProducerTest {
    private static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    private final JobService jobs = mock(JobService.class);
    private final SymbolRepository symbols = mock(SymbolRepository.class);
    private final SyncSymbolsJobProducer producer =
            new SyncSymbolsJobProducer(jobs, mock(KafkaPublisher.class), symbols);

    @Test
    void nullMissingAndMalformedExchangeConfigurationRetainsDefaultWork() {
        for (Map<String, Object> config :
                Arrays.<Map<String, Object>>asList(
                        null, Map.of(), Map.of(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, "HOSE"))) {
            var job = job(config);
            when(symbols.countAllActiveSymbolsGroupedByExchange()).thenReturn(List.of());
            when(jobs.createChildExecution(any(), any(), any(), any()))
                    .thenAnswer(
                            call -> {
                                var child = new JobExecutionHistory();
                                child.setId(UUID.randomUUID());
                                return child;
                            });
            var messages = producer.buildMessages(job, parent(job), NOW);
            assertThat(messages)
                    .extracting(message -> message.key())
                    .containsExactly("HOSE", "HNX", "UPCOM");
            assertThat(messages)
                    .allSatisfy(
                            message -> {
                                var payload = (SyncSymbolsJobMessage) message.payload();
                                assertThat(payload.metadata())
                                        .containsEntry(
                                                JobDefinitionConfig.CONFIG_KEY_SYMBOL_COUNT, 0);
                            });
            assertThat(job.getConfigJson()).isEqualTo(config);
        }
    }

    @Test
    void emptyExchangeListCreatesNoWorkAndExplicitEntriesAreNormalizedWithoutMutation() {
        when(symbols.countAllActiveSymbolsGroupedByExchange()).thenReturn(List.of());
        var empty = job(Map.of(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of()));
        assertThat(producer.buildMessages(empty, parent(empty), NOW)).isEmpty();
        verifyNoInteractions(jobs);
        var config = new HashMap<String, Object>();
        config.put(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, Arrays.asList(null, "hose"));
        config.put(JobDefinitionConfig.CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION, true);
        var job = job(config);
        when(jobs.createChildExecution(any(), any(), any(), any()))
                .thenAnswer(
                        call -> {
                            var child = new JobExecutionHistory();
                            child.setId(UUID.randomUUID());
                            return child;
                        });
        var messages = producer.buildMessages(job, parent(job), NOW);
        assertThat(messages).extracting(message -> message.key()).containsExactly("HOSE");
        assertThat(config.get(JobDefinitionConfig.CONFIG_KEY_EXCHANGES))
                .isEqualTo(Arrays.asList(null, "hose"));
    }

    private static JobDefinition job(Map<String, Object> config) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(JobDefinition.DataSource.VCI);
        job.setConfigJson(config);
        return job;
    }

    private static JobExecutionHistory parent(JobDefinition job) {
        var parent = new JobExecutionHistory();
        parent.setId(UUID.randomUUID());
        parent.setJob(job);
        return parent;
    }
}
