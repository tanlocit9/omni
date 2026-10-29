package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.messaging.SyncMetadataJobMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import tools.jackson.databind.json.JsonMapper;

class SchedulerOutboxCandidateTest {
    @Test
    void publishesAndReplaysTheFirstApprovalWithoutMutatingIntentOrRebindingRetry() {
        var work = execution(null);
        var row = row(work, Map.of());
        row.setId(UUID.randomUUID());
        String original = row.getPayload();
        var claim = new SchedulerOutboxClaim(row.getId(), UUID.randomUUID(), "owner",
                work.getId(), "topic", "key", original, 1);
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        when(histories.findByIdWithJob(work.getId())).thenReturn(Optional.of(work));
        when(repository.claimEligible(row.getId(), NOW, "owner", Duration.ofSeconds(30))).thenReturn(claim);
        var input = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg"));
        var first = service.claimEligible(row.getId(), NOW, "owner", Duration.ofSeconds(30), Map.of(input, "v1"));
        assertThat(json.readTree(first.payload()).path("metadata").path("approvedInputs").get(0)
                .path("dataVersion").asText()).isEqualTo("v1");
        var retry = service.claimEligible(row.getId(), NOW, "owner", Duration.ofSeconds(30), Map.of(input, "v2"));
        assertThat(json.readTree(retry.payload())).isEqualTo(json.readTree(first.payload()));
        assertThat(json.readTree(service.boundPayload(row.getId()))).isEqualTo(json.readTree(first.payload()));
        assertThat(row.getPayload()).isEqualTo(original);
    }
    private static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    private final SchedulerOutboxRepository repository = mock(SchedulerOutboxRepository.class);
    private final JobExecutionHistoryRepository histories =
            mock(JobExecutionHistoryRepository.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final SchedulerOutboxService service =
            new SchedulerOutboxService(repository, histories, new KafkaPublisher(null, json), json);

    @Test
    void realSerializedPreparedRootAndChildRoundTripThroughCandidateLoading() {
        for (boolean fanout : List.of(false, true)) {
            var parent = execution(null);
            var work = fanout ? execution(parent.getId()) : parent;
            work.setJob(parent.getJob());
            work.setJobRunId(parent.getJobRunId());
            work.setJobRunStepIndex(parent.getJobRunStepIndex());
            var payload =
                    new SyncMetadataJobMessage(
                            parent.getJob().getId(),
                            work.getId(),
                            work.getParentLogId(),
                            "VCI",
                            WorkType.GLOBAL,
                            "SYNC_METADATA",
                            null,
                            Map.of());
            List<SchedulerOutboxMessage> saved = new ArrayList<>();
            doAnswer(
                            invocation -> {
                                Iterable<SchedulerOutboxMessage> rows = invocation.getArgument(0);
                                rows.forEach(saved::add);
                                return saved;
                            })
                    .when(repository)
                    .saveAll(any());
            service.enqueueRunStep(
                    parent,
                    "jobs",
                    List.of(new KafkaMessage("metadata", payload)),
                    NOW,
                    fanout ? List.of(work) : List.of());
            var row = saved.getFirst();
            stubCandidate(row, work);

            var candidate = service.findCandidates(NOW, 10).getFirst();

            assertThat(json.readTree(row.getPayload()).get("executionId").asText())
                    .isEqualTo(work.getId().toString());
            assertThat(candidate.executionId()).isEqualTo(work.getId());
            assertThat(candidate.parentExecutionId()).isEqualTo(work.getParentLogId());
            assertThat(candidate.workType()).isEqualTo("GLOBAL");
            assertThat(candidate.workKey()).isEqualTo("SYNC_METADATA");
            assertThat(candidate.jobDefinition()).isSameAs(parent.getJob());
            assertThat(candidate.availableAt()).isEqualTo(NOW);
        }
    }

    @Test
    void rejectsUnexpectedMissingAndForeignParentIdentity() {
        for (boolean child : List.of(false, true)) {
            var work = execution(child ? UUID.randomUUID() : null);
            for (String parent : List.of("", UUID.randomUUID().toString())) {
                if (!child && parent.isEmpty()) continue;
                var payload = new HashMap<String, Object>();
                payload.put("executionId", work.getId().toString());
                if (!parent.isEmpty()) payload.put("parentExecutionId", parent);
                var row = row(work, payload);
                stubCandidate(row, work);
                assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(child ? "parentExecutionId mismatch" : "Unexpected");
            }
        }
    }

    @Test
    void rejectsMalformedJsonExecutionIdentityAndMissingExecution() {
        var work = execution(null);
        var row = row(work, Map.of());
        stubCandidate(row, work);
        row.setPayload("{");
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("Invalid scheduler outbox payload");
        row.setPayload("{\"executionId\":\"not-a-uuid\"}");
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("Invalid scheduler outbox executionId");
        row.setPayload(json.writeValueAsString(Map.of("executionId", work.getId().toString())));
        when(histories.findByIdWithJob(work.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("payload execution not found");
    }

    @Test
    void metadataFallbackUsesExplicitStoredIdentityAndRejectsAbsentWorkIdentity() {
        var work = execution(null);
        var row = row(work, Map.of("executionId", work.getId().toString(), "workType", " "));
        stubCandidate(row, work);
        var candidate = service.findCandidates(NOW, 10).getFirst();
        assertThat(candidate.executionId()).isEqualTo(work.getId());
        assertThat(candidate.runKey()).isEqualTo(NOW.toString());
        assertThat(candidate.workType()).isEqualTo("GLOBAL");
        work.setMetaJson(null);
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("Missing scheduler outbox work identity");
        work.setMetaJson(Map.of("workType", "GLOBAL", "workKey", " "));
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("Missing scheduler outbox work identity");
        row.setPayload(
                "{\"workType\":\"GLOBAL\",\"workKey\":\"SYNC_METADATA\",\"executionId\":null}");
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("stored work execution");
        when(repository.findById(row.getId())).thenReturn(Optional.empty());
        assertThat(service.findCandidates(NOW, 10)).isEmpty();
    }

    @Test
    void legacyEnqueueRejectsMissingOrForeignChildrenBeforeSavingIntent() {
        var parent = execution(null);
        var child = execution(parent.getId());
        var payload =
                new SyncMetadataJobMessage(
                        parent.getJob().getId(),
                        child.getId(),
                        parent.getId(),
                        "VCI",
                        WorkType.GLOBAL,
                        "SYNC_METADATA",
                        null,
                        Map.of());
        when(histories.findById(child.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(
                        () ->
                                service.enqueue(
                                        parent,
                                        "jobs",
                                        List.of(new KafkaMessage("metadata", payload)),
                                        NOW))
                .hasMessageContaining("child execution not found");
        child.setParentLogId(UUID.randomUUID());
        when(histories.findById(child.getId())).thenReturn(Optional.of(child));
        assertThatThrownBy(
                        () ->
                                service.enqueue(
                                        parent,
                                        "jobs",
                                        List.of(new KafkaMessage("metadata", payload)),
                                        NOW))
                .hasMessageContaining("belongs to another parent");
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    private void stubCandidate(SchedulerOutboxMessage row, JobExecutionHistory work) {
        row.setId(UUID.randomUUID());
        when(repository.findPendingCandidateIds(NOW, 10)).thenReturn(List.of(row.getId()));
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        when(histories.findByIdWithJob(work.getId())).thenReturn(Optional.of(work));
    }

    @Test
    void normalizedChildIntentKeepsApprovedVersionsOnWorkAndRejectsParentAlias() {
        var parent = execution(null);
        parent.setJobRunId(null);
        var child = execution(parent.getId());
        child.setJobRunId(null);
        child.setJob(parent.getJob());
        var row =
                row(
                        child,
                        Map.of(
                                "executionId",
                                child.getId().toString(),
                                "parentExecutionId",
                                parent.getId().toString()));
        row.setId(UUID.randomUUID());
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        when(histories.findByIdWithJob(child.getId())).thenReturn(Optional.of(child));
        when(repository.claimEligible(any(), any(), any(), any()))
                .thenReturn(
                        new com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim(
                                row.getId(),
                                UUID.randomUUID(),
                                "owner",
                                parent.getId(),
                                "jobs",
                                "key",
                                row.getPayload(),
                                1));
        var input =
                com.omni.platform.modules.scheduler.dependencies.DatasetRef.of(
                        "eod", Map.of("exchange", "HOSE"));
        assertThat(
                        service.claimEligible(
                                row.getId(),
                                NOW,
                                "owner",
                                java.time.Duration.ofMinutes(1),
                                Map.of(input, "v1")))
                .isNotNull();
        assertThat(child.getMetaJson()).containsKey("approvedInputs");
        assertThat(parent.getMetaJson()).doesNotContainKey("approvedInputs");
        row.setExecution(parent);
        child.setParentLogId(UUID.randomUUID());
        row.setPayload(
                json.writeValueAsString(
                        Map.of(
                                "executionId",
                                child.getId().toString(),
                                "parentExecutionId",
                                child.getParentLogId().toString())));
        assertThatThrownBy(
                        () ->
                                service.claimEligible(
                                        row.getId(),
                                        NOW,
                                        "owner",
                                        java.time.Duration.ofMinutes(1),
                                        Map.of(input, "v2")))
                .hasMessageContaining("stored work execution");
    }

    @Test
    void safeBatchSkipsPoisonAndDeletedIntentWithoutHidingValidCandidates() {
        var work = execution(null);
        var good = row(work, Map.of("executionId", work.getId().toString()));
        good.setId(UUID.randomUUID());
        var bad = row(execution(null), Map.of());
        bad.setId(UUID.randomUUID());
        bad.setPayload("{");
        UUID missing = UUID.randomUUID();
        var ids = List.of(bad.getId(), missing, good.getId());
        when(repository.findAllCandidates(ids)).thenReturn(List.of(bad, good));
        assertThat(service.findCandidatesByIdsSafely(ids))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.messageId()).isEqualTo(good.getId()));
        assertThat(service.findCandidatesByIdsSafely(List.of())).isEmpty();
    }

    @Test
    void boundedBatchUsesLoadedMembershipPreservesOrderAndRejectsForeignRunPayload() {
        var a = execution(null);
        var b = execution(null);
        var first = row(a, Map.of("executionId", a.getId().toString()));
        var second = row(b, Map.of("executionId", b.getId().toString()));
        first.setId(UUID.randomUUID());
        second.setId(UUID.randomUUID());
        UUID deleted = UUID.randomUUID();
        List<UUID> ids = List.of(first.getId(), second.getId(), deleted);
        when(repository.findAllCandidates(ids)).thenReturn(List.of(second, first));
        assertThat(service.findCandidatesByIds(ids))
                .extracting(candidate -> candidate.executionId())
                .containsExactly(a.getId(), b.getId());
        org.mockito.Mockito.verifyNoInteractions(histories);
        first.setPayload(json.writeValueAsString(Map.of("executionId", b.getId().toString())));
        assertThatThrownBy(() -> service.findCandidatesByIds(ids))
                .hasMessageContaining("stored work execution");
        assertThat(service.findCandidatesByIds(List.of())).isEmpty();
        assertThatThrownBy(() -> service.findCandidatesByIds(List.of(first.getId(), first.getId())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                service.findCandidatesByIds(
                                        java.util.stream.IntStream.range(0, 401)
                                                .mapToObj(i -> UUID.randomUUID())
                                                .toList()))
                .isInstanceOf(IllegalArgumentException.class);
        first.getExecution().setJobRunId(null);
        b.setJobRunId(null);
        b.setParentLogId(a.getId());
        b.setJob(a.getJob());
        first.setPayload(
                json.writeValueAsString(
                        Map.of(
                                "executionId",
                                b.getId().toString(),
                                "parentExecutionId",
                                a.getId().toString())));
        second.setPayload(first.getPayload());
        when(histories.findByIdWithJob(b.getId())).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.findCandidatesByIds(ids))
                .hasMessageContaining("stored work execution");
        assertThat(service.findCandidatesByIdsSafely(ids)).hasSize(1);
    }

    @Test
    void lostClaimCannotRewriteApprovedInputVersions() {
        var work = execution(null);
        var row = row(work, Map.of());
        row.setId(UUID.randomUUID());
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        var ref =
                com.omni.platform.modules.scheduler.dependencies.DatasetRef.of(
                        "eod", Map.of("exchange", "HOSE"));
        var before = work.getMetaJson();
        assertThat(
                        service.claimEligible(
                                row.getId(),
                                NOW,
                                "owner",
                                java.time.Duration.ofMinutes(1),
                                Map.of(ref, "v1")))
                .isNull();
        assertThat(work.getMetaJson()).isSameAs(before);
    }

    @Test
    void explicitDefinitionMustMatchStoredWorkAndMissingExecutionCannotFallBack() {
        var work = execution(null);
        var row = row(work, Map.of("jobDefinitionId", work.getJob().getId().toString()));
        stubCandidate(row, work);
        assertThat(service.findCandidates(NOW, 10)).hasSize(1);
        row.setPayload(
                json.writeValueAsString(
                        Map.of(
                                "executionId",
                                work.getId().toString(),
                                "jobDefinitionId",
                                UUID.randomUUID().toString())));
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("jobDefinitionId mismatch");
        row.setPayload("{}");
        assertThatThrownBy(() -> service.findCandidates(NOW, 10))
                .hasMessageContaining("stored work execution");
    }

    private SchedulerOutboxMessage row(JobExecutionHistory work, Map<String, ?> payload) {
        var row = new SchedulerOutboxMessage();
        row.setExecution(work);
        row.setAvailableAt(NOW);
        var fields = new HashMap<String, Object>(payload);
        fields.putIfAbsent("executionId", work.getId().toString());
        row.setPayload(json.writeValueAsString(fields));
        return row;
    }

    private static JobExecutionHistory execution(UUID parentId) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        var work = new JobExecutionHistory();
        work.setId(UUID.randomUUID());
        work.setJob(job);
        work.setJobRunId(UUID.randomUUID());
        work.setJobRunStepIndex(0);
        work.setUsedSource(JobDefinition.DataSource.VCI);
        work.setParentLogId(parentId);
        work.setTriggeredAt(NOW);
        work.setMetaJson(Map.of("workType", "GLOBAL", "workKey", "SYNC_METADATA"));
        return work;
    }
}
