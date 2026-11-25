package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.modules.scheduler.metadata.OutputStorageHeads;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class SchedulerOutboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final SchedulerOutboxRepository repository = mock(SchedulerOutboxRepository.class);
    private final JobExecutionHistoryRepository histories =
            mock(JobExecutionHistoryRepository.class);
    private final OutputStorageHeads heads = mock(OutputStorageHeads.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final SchedulerOutboxService service =
            new SchedulerOutboxService(repository, histories, new KafkaPublisher(null, json), json);

    @Test
    void missingOrTerminalIntentCannotClaimFreezeOrBindNewInputs() {
        ReflectionTestUtils.setField(service, "storageHeads", heads);
        UUID missing = UUID.randomUUID();
        assertThat(service.claimEligible(missing, NOW, "worker", LEASE, Map.of())).isNull();
        var work = work(null, Map.of());
        var row = intent(work, Map.of());
        for (var status :
                List.of(
                        SchedulerOutboxMessage.Status.BLOCKED,
                        SchedulerOutboxMessage.Status.PUBLISHED)) {
            row.setStatus(status);
            assertThat(
                            service.claimEligible(
                                    row.getId(),
                                    NOW,
                                    "worker",
                                    LEASE,
                                    Map.of(
                                            DatasetRef.of(
                                                    "eod",
                                                    Map.of("exchange", "hose", "code", "mbb")),
                                            "v1")))
                    .isNull();
        }
        assertThat(work.getMetaJson()).isEmpty();
        verify(repository, never()).claimEligible(any(), any(), any(), any());
        verifyNoInteractions(heads);
    }

    @Test
    void lostClaimLeavesStorageHeadsAndApprovedInputsUntouched() {
        ReflectionTestUtils.setField(service, "storageHeads", heads);
        var work = work(null, Map.of("marker", "original"));
        var row = intent(work, Map.of());
        assertThat(
                        service.claimEligible(
                                row.getId(),
                                NOW,
                                "worker",
                                LEASE,
                                Map.of(
                                        DatasetRef.of(
                                                "eod", Map.of("exchange", "hose", "code", "mbb")),
                                        "v1")))
                .isNull();
        assertThat(work.getMetaJson()).containsOnlyKeys("marker");
        verifyNoInteractions(heads);
    }

    @Test
    void successfulClaimFreezesTheWorkAndPublishesVersionedBasesWithoutRewritingIntent() {
        ReflectionTestUtils.setField(service, "storageHeads", heads);
        var work = work(null, Map.of());
        var row =
                intent(
                        work,
                        Map.of(
                                "metadata",
                                Map.of("outputCompletion", Map.of("storagePolicy", "VERSIONED"))));
        var original = row.getPayload();
        var bases =
                List.<Map<String, Object>>of(
                        Map.of(
                                "dataset",
                                "eod",
                                "partition",
                                Map.of("exchange", "hose", "code", "mbb"),
                                "storageVersionId",
                                "immutable-base",
                                "generation",
                                4));
        when(heads.read(work.getId())).thenReturn(bases);
        var leased = claim(row);
        when(repository.claimEligible(row.getId(), NOW, "worker", LEASE)).thenReturn(leased);
        var result = service.claimEligible(row.getId(), NOW, "worker", LEASE, Map.of());
        verify(heads).freeze(work.getId());
        assertThat(result.claimToken()).isEqualTo(leased.claimToken());
        assertThat(result.executionId()).isEqualTo(work.getId());
        assertThat(
                        json.readTree(result.payload())
                                .path("metadata")
                                .path("outputCompletion")
                                .path("storageBases"))
                .isEqualTo(json.valueToTree(bases));
        assertThat(row.getPayload()).isEqualTo(original);
        assertThat(json.readTree(service.boundPayload(row.getId())))
                .isEqualTo(json.readTree(result.payload()));
    }

    @Test
    void durableParentRefreshIsReadAtPublishTimeAndDoesNotModifySealedIntent() {
        ReflectionTestUtils.setField(service, "jdbc", jdbc);
        var work = work(UUID.randomUUID(), Map.of());
        var row = intent(work, Map.of("metadata", Map.of("traceMarker", "keep")));
        var original = row.getPayload();
        var sql = "SELECT meta_json->>'dispatchDataPolicy' FROM job_execution_histories WHERE id=?";
        when(jdbc.queryForObject(sql, String.class, work.getParentLogId()))
                .thenReturn(null, "REFRESH", "PREFER_EXISTING");
        assertThat(service.boundPayload(row.getId())).isEqualTo(original);
        var published = json.readTree(service.boundPayload(row.getId())).path("metadata");
        assertThat(published.path("traceMarker").asText()).isEqualTo("keep");
        assertThat(published.path("backfillPolicy").path("dataPolicy").asText())
                .isEqualTo("REFRESH");
        assertThat(published.path("backfillPolicy").path("verifyEodParquet").asBoolean()).isTrue();
        assertThatThrownBy(() -> service.boundPayload(row.getId()))
                .hasMessageContaining("Invalid dispatch data policy");
        assertThat(row.getPayload()).isEqualTo(original);
    }

    @Test
    void firstApprovedInputsOverridePayloadMetadataButMalformedBindingsFailClosed() {
        var approved = List.of(Map.of("dataset", "eod", "dataVersion", "frozen-v1"));
        var work = work(null, Map.of("approvedInputs", approved));
        var row = intent(work, Map.of("metadata", Map.of("approvedInputs", List.of("untrusted"))));
        assertThat(
                        json.readTree(service.boundPayload(row.getId()))
                                .path("metadata")
                                .path("approvedInputs"))
                .isEqualTo(json.valueToTree(approved));
        work.setMetaJson(Map.of("approvedInputs", "not-an-array"));
        assertThatThrownBy(() -> service.boundPayload(row.getId()))
                .hasMessageContaining("Invalid approved input binding");
    }

    @Test
    void boundBasesRequireAnOutputDeclarationAndMissingMetadataCanBeReconstructed() {
        ReflectionTestUtils.setField(service, "storageHeads", heads);
        var work = work(null, Map.of("approvedInputs", List.of()));
        var row = intent(work, Map.of());
        assertThat(
                        json.readTree(service.boundPayload(row.getId()))
                                .path("metadata")
                                .path("approvedInputs")
                                .isArray())
                .isTrue();
        when(heads.read(work.getId())).thenReturn(List.of(Map.of("dataset", "eod")));
        assertThatThrownBy(() -> service.boundPayload(row.getId()))
                .hasMessageContaining("no output declaration");
        work.setMetaJson(null);
        assertThatThrownBy(() -> service.boundPayload(row.getId()))
                .hasMessageContaining("no output declaration");
    }

    @Test
    void retryAndAcknowledgementKeepTheLeaseFenceAndBoundDiagnosticText() {
        var work = work(null, Map.of());
        var row = intent(work, Map.of());
        var leased = claim(row);
        when(repository.markPublished(row.getId(), leased.claimToken(), "worker", NOW))
                .thenReturn(false);
        assertThat(service.markDelivered(leased, NOW)).isFalse();
        service.scheduleRetry(leased, NOW, "  failure\r\nwith\ttabs  ");
        verify(repository)
                .markFailed(row.getId(), leased.claimToken(), "worker", NOW, "failure with tabs");
        service.scheduleRetry(leased, NOW, "x".repeat(5000));
        verify(repository)
                .markFailed(row.getId(), leased.claimToken(), "worker", NOW, "x".repeat(4000));
        service.markWaiting(row.getId(), NOW, null);
        verify(repository).markWaiting(row.getId(), NOW, "unknown");
        service.markBlocked(row.getId(), NOW, " blocked\nreason ");
        verify(repository).markBlocked(row.getId(), NOW, "blocked reason");
        when(repository.renewForPublish(leased, NOW, LEASE)).thenReturn(false);
        assertThat(service.renewForPublish(leased, NOW, LEASE)).isFalse();
    }

    private JobExecutionHistory work(UUID parent, Map<String, Object> metadata) {
        var definition = new JobDefinition();
        definition.setId(UUID.randomUUID());
        var execution = new JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setJob(definition);
        execution.setParentLogId(parent);
        execution.setTriggeredAt(NOW);
        execution.setMetaJson(metadata);
        return execution;
    }

    private SchedulerOutboxMessage intent(JobExecutionHistory work, Map<String, Object> fields) {
        var payload = new java.util.LinkedHashMap<String, Object>(fields);
        payload.put("executionId", work.getId().toString());
        if (work.getParentLogId() != null)
            payload.put("parentExecutionId", work.getParentLogId().toString());
        var row = new SchedulerOutboxMessage();
        row.setId(UUID.randomUUID());
        row.setExecution(work);
        row.setPayload(json.writeValueAsString(payload));
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        when(histories.findByIdWithJob(work.getId())).thenReturn(Optional.of(work));
        return row;
    }

    private SchedulerOutboxClaim claim(SchedulerOutboxMessage row) {
        return new SchedulerOutboxClaim(
                row.getId(),
                UUID.randomUUID(),
                "worker",
                row.getExecution().getId(),
                "jobs",
                "key",
                row.getPayload(),
                1);
    }
}
