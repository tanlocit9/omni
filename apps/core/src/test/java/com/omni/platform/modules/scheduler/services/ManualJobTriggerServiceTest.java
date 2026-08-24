package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.scheduler.config.ManualTriggerProperties;
import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyContextFactory;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard;
import com.omni.platform.modules.scheduler.dependencies.JobDependencyGuard.GuardResult;
import com.omni.platform.modules.scheduler.dependencies.JobExecutionContext;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerRequest;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.ManualJobTrigger;
import com.omni.platform.modules.scheduler.producers.JobProducer;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.ManualJobTriggerRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;

@ExtendWith(MockitoExtension.class)
class ManualJobTriggerServiceTest {
    @Mock private JobDefinitionRepository definitions;
    @Mock private JobExecutionHistoryRepository executions;
    @Mock private ManualJobTriggerRepository triggers;
    @Mock private SchedulerClaimService claims;
    @Mock private JobDependencyContextFactory contexts;
    @Mock private JobDependencyGuard guard;
    @Mock private JobProducerRegistry producers;
    @Mock private JobProducer producer;

    private ManualJobTriggerService service;
    private JobDefinition definition;

    @BeforeEach
    void setUp() {
        definition = definition(true);
        service = new ManualJobTriggerService(definitions, executions, triggers, claims, contexts, guard, producers,
                new ManualTriggerProperties(java.util.List.of("SYNC_INDICATORS:ANALYZER")));
        lenient().when(triggers.saveAndFlush(any())).thenAnswer(invocation -> persisted(invocation.getArgument(0)));
        lenient().when(triggers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void acceptedTriggerUsesExactClaimDependencyGuardProducerAndAuditMetadata() {
        UUID executionId = UUID.randomUUID();
        SchedulerClaim claim = claim();
        JobExecutionContext context = new JobExecutionContext(definition, "check", Map.of());
        when(triggers.findByActorAndIdempotencyKey("alice", "request-1")).thenReturn(Optional.empty());
        when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(claims.claimJobDefinition(eq(definition.getId()), any(Instant.class), eq("alice")))
                .thenReturn(Optional.of(claim));
        when(contexts.create(definition)).thenReturn(context);
        when(guard.checkDependencies(context)).thenReturn(GuardResult.ready());
        when(producers.getProducer(JobType.SYNC_INDICATORS)).thenReturn(producer);
        when(producer.prepareManualDispatch(eq(definition), eq(claim), any(), eq(Map.of()), any()))
                .thenReturn(executionId);

        var result = service.trigger(definition.getId(), " alice ", request("request-1"));

        assertThat(result.state()).isEqualTo("ACCEPTED");
        assertThat(result.executionId()).isEqualTo(executionId);
        assertThat(result.duplicate()).isFalse();
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(producer).prepareManualDispatch(eq(definition), eq(claim), any(), eq(Map.of()), metadata.capture());
        assertThat(metadata.getValue().toString()).contains("alice", "request-1", "operator recovery");
        assertThat(definition.getNextRun()).isEqualTo(Instant.parse("2026-08-25T03:00:00Z"));
    }

    @Test
    void duplicateRequestReturnsDurableAuditWithoutClaimingAgain() {
        ManualJobTrigger existing = audit("alice", "same-key", definition);
        existing.setState(ManualJobTrigger.ManualTriggerState.ACCEPTED);
        existing.setExecutionId(UUID.randomUUID());
        when(triggers.findByActorAndIdempotencyKey("alice", "same-key")).thenReturn(Optional.of(existing));

        var result = service.trigger(definition.getId(), "alice", request("same-key"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.executionId()).isEqualTo(existing.getExecutionId());
        verify(claims, never()).claimJobDefinition(any(), any(), any());
    }

    @Test
    void blockedDependencyReleasesOnlyOwnedClaimAndDoesNotDispatch() {
        SchedulerClaim claim = claim();
        JobExecutionContext context = new JobExecutionContext(definition, "check", Map.of());
        when(triggers.findByActorAndIdempotencyKey("alice", "blocked-1")).thenReturn(Optional.empty());
        when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(claims.claimJobDefinition(eq(definition.getId()), any(), eq("alice"))).thenReturn(Optional.of(claim));
        when(contexts.create(definition)).thenReturn(context);
        when(guard.checkDependencies(context)).thenReturn(GuardResult.blocked(java.util.List.of(),
                "missing s3://private-bucket/eod/token password=hunter2"));

        var result = service.trigger(definition.getId(), "alice", request("blocked-1"));

        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.blockReason()).doesNotContain("private-bucket", "hunter2");
        verify(claims).releaseClaim(claim.jobDefinitionId(), claim.claimToken(), claim.claimedBy());
        verify(producers, never()).getProducer(any());
    }

    @Test
    void inactiveOrNotAllowListedDefinitionCannotAcquireClaim() {
        definition.setIsActive(false);
        when(triggers.findByActorAndIdempotencyKey("alice", "inactive-1")).thenReturn(Optional.empty());
        when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));

        var result = service.trigger(definition.getId(), "alice", request("inactive-1"));

        assertThat(result.state()).isEqualTo("CONFLICT");
        verify(claims, never()).claimJobDefinition(any(), any(), any());
    }

    @Test
    void rejectsAnonymousInvalidIdempotencyAndRuntimeParameters() {
        assertThatThrownBy(() -> service.trigger(definition.getId(), " ", request("key")))
                .isInstanceOf(JobOperationException.class).hasMessageContaining("identity");
        assertThatThrownBy(() -> service.trigger(definition.getId(), "alice", request("bad key")))
                .isInstanceOf(JobOperationException.class).hasMessageContaining("unsupported");
        assertThatThrownBy(() -> service.trigger(definition.getId(), "alice",
                new ManualTriggerRequest("valid-key", "reason", Map.of("force", true))))
                .isInstanceOf(JobOperationException.class).hasMessageContaining("does not accept");
    }

    @Test
    void triggerStatusIsScopedToTheAuthenticatedActor() {
        ManualJobTrigger existing = audit("alice", "status-1", definition);
        when(triggers.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.triggerStatus(existing.getId(), "bob"))
                .isInstanceOf(JobOperationException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    private static ManualTriggerRequest request(String key) {
        return new ManualTriggerRequest(key, "operator recovery", Map.of());
    }

    private static JobDefinition definition(boolean active) {
        JobDefinition value = new JobDefinition();
        value.setId(UUID.randomUUID());
        value.setTitle("Indicators");
        value.setSource(DataSource.ANALYZER);
        value.setJobType(JobType.SYNC_INDICATORS);
        value.setIsActive(active);
        value.setNextRun(Instant.parse("2026-08-25T03:00:00Z"));
        return value;
    }

    private SchedulerClaim claim() {
        return new SchedulerClaim(definition.getId(), UUID.randomUUID(), "manual:alice", Instant.now(),
                Instant.now().plus(Duration.ofMinutes(5)), definition.getNextRun());
    }

    private static ManualJobTrigger audit(String actor, String key, JobDefinition job) {
        ManualJobTrigger value = new ManualJobTrigger();
        value.setId(UUID.randomUUID());
        value.setActor(actor);
        value.setIdempotencyKey(key);
        value.setReason("reason");
        value.setParameters(Map.of());
        value.setJobDefinition(job);
        value.setRequestedAt(Instant.now());
        value.setState(ManualJobTrigger.ManualTriggerState.REQUESTED);
        return value;
    }

    private static ManualJobTrigger persisted(ManualJobTrigger value) {
        if (value.getId() == null) value.setId(UUID.randomUUID());
        return value;
    }
}
