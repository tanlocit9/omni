package com.omni.platform.modules.scheduler.expansion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.producers.SyncIndicatorsJobProducer;
import com.omni.platform.modules.scheduler.producers.SyncMetadataJobProducer;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.modules.scheduler.runs.JobRunWorkContext;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

class ExpansionRoutesTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void materializationReusesAcceptedMembershipWithoutAnotherCatalogRead() {
        var jobs = mock(JobService.class);
        var repository = mock(SymbolRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var entities = mock(EntityManager.class);
        SymbolKeyProjection symbol = new SymbolKeyProjection() {
            public String getCode() { return "HPG"; }
            public String getExchange() { return "HOSE"; }
        };
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<SymbolKeyProjection>>any(),
                any(), any(), any(), any())).thenReturn(List.of(symbol));
        doAnswer(inv -> { ((JobExecutionHistory) inv.getArgument(0)).setId(UUID.randomUUID()); return null; })
                .when(entities).persist(any());
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS);
        var root = parent(definition);
        root.setJobRunId(UUID.randomUUID());
        root.setJobRunStepIndex(0);
        root.setMetaJson(Map.of("businessDate", "2026-11-02", "expansionRequest",
                Map.of("dateFrom", "2026-10-30", "dateTo", "2026-11-02")));
        var producer = new SyncIndicatorsJobProducer(jobs, null, repository);
        var json = JsonMapper.builder().build();
        var accepted = producer.acceptRunStep(definition, root, now,
                JobRunWorkContext.preview(root, jdbc, json));
        producer.persistAcceptedRunStep(root, accepted, now,
                new JobRunWorkContext(root, entities, jdbc, json, 5000));
        ArgumentCaptor<List<KafkaMessage>> sent = ArgumentCaptor.forClass((Class) List.class);
        verify(jobs).enqueueRunDispatch(eq(root), nullable(String.class), sent.capture(), eq(now), any());
        assertThat(sent.getValue()).extracting(m -> ((ExpandedJobMessage) m.payload()).atomicWorkId())
                .containsExactlyElementsOf(accepted.messages().stream()
                        .map(m -> ((ExpandedJobMessage) m.payload()).atomicWorkId()).toList());
        assertThat(sent.getValue()).extracting(m -> ((ExpandedJobMessage) m.payload()).executionId())
                .doesNotContainAnyElementsOf(accepted.children().stream().map(JobExecutionHistory::getId).toList());
        verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<SymbolKeyProjection>>any(),
                any(), any(), any(), any());
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(repository);
    }
    @Test
    void graphPreviewUsesActualI8ProducerWithoutExecutionOutboxOrKafkaWrites() {
        var jobs = mock(JobService.class);
        var publisher = mock(KafkaPublisher.class);
        var repository = mock(SymbolRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        SymbolKeyProjection symbol = new SymbolKeyProjection() {
            public String getCode() { return "HPG"; }
            public String getExchange() { return "HOSE"; }
        };
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<SymbolKeyProjection>>any(),
                any(), any(), any(), any())).thenReturn(List.of(symbol));
        var producer = new SyncIndicatorsJobProducer(jobs, publisher, repository);
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS);
        var request = Map.<String, Object>of("dateFrom", "2026-10-30", "dateTo", "2026-11-02");
        var preview = producer.previewAtomicWork(definition, request, now, jdbc,
                JsonMapper.builder().build());
        assertThat(preview).hasSize(2);
        assertThat(preview).extracting(ExpandedJobMessage::businessDate)
                .containsExactly(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-02"));
        assertThat(preview).allSatisfy(work -> assertThat(work.workKey()).isEqualTo("HOSE-HPG"));
        assertThat(definition.getConfigJson()).isEmpty();
        verifyNoInteractions(jobs, publisher, repository);
        verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<SymbolKeyProjection>>any(),
                any(), any(), any(), any());
        verifyNoMoreInteractions(jdbc);
    }
    private final Instant now = Instant.parse("2026-11-02T05:00:00Z");

    private JobDefinition job(JobDefinition.JobType type) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(JobDefinition.DataSource.ANALYZER);
        job.setJobType(type);
        job.setConfigJson(Map.of());
        return job;
    }

    private JobExecutionHistory parent(JobDefinition job) {
        var p = new JobExecutionHistory();
        p.setId(UUID.randomUUID());
        p.setJob(job);
        p.setMetaJson(new LinkedHashMap<>());
        return p;
    }

    private SchedulerClaim claim(JobDefinition job) {
        return new SchedulerClaim(
                job.getId(), UUID.randomUUID(), "test", now, now.plus(Duration.ofMinutes(5)), now);
    }

    private JobService jobs(JobExecutionHistory root) {
        var jobs = mock(JobService.class);
        when(jobs.prepareClaimedExecution(any(), any(), any(), any())).thenReturn(root);
        when(jobs.prepareManualClaimedExecution(any(), any(), any(), any(), any()))
                .thenReturn(root);
        when(jobs.createChildExecution(any(), any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            var child = parent(root.getJob());
                            child.setParentLogId(root.getId());
                            return child;
                        });
        return jobs;
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void legacyAndManualExpandSameClosedSymbolSelectionAcrossWeekdays() {
        for (boolean manual : List.of(false, true)) {
            var job = job(JobDefinition.JobType.SYNC_INDICATORS);
            var root = parent(job);
            root.setMetaJson(
                    Map.of(
                            "expansionRequest",
                            Map.of(
                                    "dateFrom",
                                    "2026-10-30",
                                    "dateTo",
                                    "2026-11-02",
                                    "scope",
                                    Map.of("type", "EXCHANGE", "key", "HOSE"))));
            var jobs = jobs(root);
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
            var producer = new SyncIndicatorsJobProducer(jobs, null, symbols);
            if (manual) producer.prepareManualDispatch(job, claim(job), now, Map.of(), Map.of());
            else producer.prepareDispatch(job, claim(job), now);
            ArgumentCaptor<List<KafkaMessage>> sent = ArgumentCaptor.forClass((Class) List.class);
            verify(jobs).enqueueDispatch(eq(root), any(), sent.capture(), eq(now));
            assertThat(sent.getValue()).hasSize(2);
            var first = (ExpandedJobMessage) sent.getValue().getFirst().payload();
            var last = (ExpandedJobMessage) sent.getValue().getLast().payload();
            assertThat(first.businessDate().toString()).isEqualTo("2026-10-30");
            assertThat(last.businessDate().toString()).isEqualTo("2026-11-02");
            assertThat(last.atomicWorkId()).isNotEqualTo(first.atomicWorkId());
            assertThat(last.executionId()).isNotEqualTo(first.executionId());
            verify(symbols, times(1)).findBySectorCodesAndLevel(any(), anyInt());
            assertThat(root.getMetaJson().get("expandedWorkIds")).isInstanceOf(List.class);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void jobRunMetadataUsesChildAndSameAtomicEnvelopeBeforeClosing() {
        var job = job(JobDefinition.JobType.SYNC_METADATA);
        var root = parent(job);
        root.setJobRunId(UUID.randomUUID());
        root.setJobRunStepIndex(0);
        root.setMetaJson(Map.of("businessDate", "2026-11-02", "runPreparationVersion", "1"));
        var jobs = jobs(root);
        var entities = mock(EntityManager.class);
        doAnswer(
                        inv -> {
                            ((JobExecutionHistory) inv.getArgument(0)).setId(UUID.randomUUID());
                            return null;
                        })
                .when(entities)
                .persist(any());
        var context =
                new JobRunWorkContext(root, entities, null, JsonMapper.builder().build(), 5000);
        var producer = new SyncMetadataJobProducer(jobs, null);
        assertThat(producer.prepareRunStep(job, root, now, context)).isEqualTo(1);
        ArgumentCaptor<List<KafkaMessage>> sent = ArgumentCaptor.forClass((Class) List.class);
        verify(jobs).enqueueRunDispatch(eq(root), any(), sent.capture(), eq(now), any());
        var work = (ExpandedJobMessage) sent.getValue().getFirst().payload();
        assertThat(work.executionId()).isNotEqualTo(root.getId());
        assertThat(context.children()).hasSize(1);
        assertThat(context.children().getFirst().getMetaJson())
                .containsKeys("workExpansion", "workIdentity", "workBusinessDate", "businessDate");
    }
}
