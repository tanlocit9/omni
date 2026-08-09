package com.omni.platform.modules.scheduler.producers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SectorTransitionAnalyzeJobMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

@ExtendWith(MockitoExtension.class)
class SectorTransitionAnalyzeJobProducerTest {

    @Mock
    private JobService jobService;

    @Mock
    private KafkaPublisher kafkaPublisher;

    @Mock
    private SymbolRepository symbolRepository;

    @Test
    void resolvesEmptyConfiguredUniverseToAllEligibleSectorsAndFocusedOutput() {
        SectorTransitionAnalyzeJobProducer producer = new SectorTransitionAnalyzeJobProducer(
                jobService,
                kafkaPublisher,
                symbolRepository);
        JobDefinition job = job(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES, List.of(),
                JobDefinitionConfig.CONFIG_KEY_FOCUS_SECTOR_CODES, List.of(" banks "),
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, 2));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        JobExecutionHistory child = execution(UUID.randomUUID());
        when(symbolRepository.findDistinctSectorCodesByLevel(null, 2))
                .thenReturn(List.of("BANKS", "REAL_ESTATE", "TECH"));
        when(jobService.createChildExecution(eq(parent.getId()), eq(JobDefinitionConfig.SECTOR_TRANSITION_STRATEGY_V1),
                any(), any()))
                .thenReturn(child);

        List<KafkaMessage> messages = producer.buildMessages(job, parent, Instant.parse("2026-08-09T00:00:00Z"));

        assertThat(messages).hasSize(1);
        SectorTransitionAnalyzeJobMessage payload = (SectorTransitionAnalyzeJobMessage) messages.get(0).payload();
        assertThat(payload.sectorCodes()).containsExactly("BANKS", "REAL_ESTATE", "TECH");
        assertThat(payload.focusSectorCodes()).containsExactly("BANKS");
        assertThat(payload.metadata())
                .containsEntry("configuredSectorCodes", List.of())
                .containsEntry("configuredFocusSectorCodes", List.of("BANKS"))
                .containsEntry("resolvedUniverse", List.of("BANKS", "REAL_ESTATE", "TECH"))
                .containsEntry("resolvedFocus", List.of("BANKS"));
    }

    @Test
    void resolvesEmptyFocusToFullResolvedUniverse() {
        SectorTransitionAnalyzeJobProducer producer = new SectorTransitionAnalyzeJobProducer(
                jobService,
                kafkaPublisher,
                symbolRepository);
        JobDefinition job = job(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES, List.of("BANKS", "TECH"),
                JobDefinitionConfig.CONFIG_KEY_FOCUS_SECTOR_CODES, List.of(),
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, 2));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        JobExecutionHistory child = execution(UUID.randomUUID());
        when(symbolRepository.findDistinctSectorCodesByLevel(new String[] { "BANKS", "TECH" }, 2))
                .thenReturn(List.of("BANKS", "TECH"));
        when(jobService.createChildExecution(eq(parent.getId()), eq(JobDefinitionConfig.SECTOR_TRANSITION_STRATEGY_V1),
                any(), any()))
                .thenReturn(child);

        List<KafkaMessage> messages = producer.buildMessages(job, parent, Instant.parse("2026-08-09T00:00:00Z"));

        SectorTransitionAnalyzeJobMessage payload = (SectorTransitionAnalyzeJobMessage) messages.get(0).payload();
        assertThat(payload.focusSectorCodes()).containsExactly("BANKS", "TECH");
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(jobService).createChildExecution(eq(parent.getId()), eq(JobDefinitionConfig.SECTOR_TRANSITION_STRATEGY_V1),
                metadataCaptor.capture(), any());
        assertThat(metadataCaptor.getValue()).containsEntry("resolvedFocus", List.of("BANKS", "TECH"));
    }

    @Test
    void rejectsFocusOutsideResolvedUniverse() {
        SectorTransitionAnalyzeJobProducer producer = new SectorTransitionAnalyzeJobProducer(
                jobService,
                kafkaPublisher,
                symbolRepository);
        JobDefinition job = job(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES, List.of("BANKS"),
                JobDefinitionConfig.CONFIG_KEY_FOCUS_SECTOR_CODES, List.of("TECH"),
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, 2));
        JobExecutionHistory parent = execution(UUID.randomUUID());
        when(symbolRepository.findDistinctSectorCodesByLevel(new String[] { "BANKS" }, 2))
                .thenReturn(List.of("BANKS"));

        assertThatThrownBy(() -> producer.buildMessages(job, parent, Instant.parse("2026-08-09T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("focusSectorCodes must be within resolved sectorCodes universe")
                .hasMessageContaining("TECH");
    }

    private JobDefinition job(Map<String, Object> config) {
        JobDefinition job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(DataSource.ANALYZER);
        job.setJobType(JobType.SECTOR_TRANSITION_ANALYZE);
        job.setConfigJson(config);
        return job;
    }

    private JobExecutionHistory execution(UUID id) {
        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setId(id);
        return execution;
    }
}
