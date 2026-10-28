package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;

class SchedulerEntryClaimTest {
    @Test
    void enabledModeResolvesOnlyKnownTerminalDefinitionsAndHandlesMissingEntries() {
        var definitions = mock(JobDefinitionRepository.class);
        var topology = new StaticJobTopologyProvider();
        var properties = new SchedulerProperties("test", null);
        var service = new SchedulerClaimService(definitions, properties, topology);
        ReflectionTestUtils.setField(service, "jobRunPreparationEnabled", true);
        var entry = topology.definitionDependencies().scheduledEntries().getFirst();
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        when(definitions.findBySourceAndJobTypeAndCronExpr(any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(definitions.findBySourceAndJobTypeAndCronExpr(
                        entry.source(), entry.jobType(), entry.cronExpression()))
                .thenReturn(Optional.of(job));
        var now = Instant.now();
        service.claimDueJobs(now);
        verify(definitions)
                .claimDueJobs(
                        now,
                        "test",
                        properties.claim().leaseDuration(),
                        properties.claim().batchSize(),
                        List.of(job.getId()));
        verify(definitions, never()).claimDueJobs(any(), any(), any(), anyInt());
    }

    @Test
    void disabledModeRetainsLegacyClaimAndManualReleaseAreUnchanged() {
        var definitions = mock(JobDefinitionRepository.class);
        var properties = new SchedulerProperties("test", null);
        var service =
                new SchedulerClaimService(definitions, properties, new StaticJobTopologyProvider());
        var now = Instant.now();
        service.claimDueJobs(now);
        verify(definitions)
                .claimDueJobs(
                        now,
                        "test",
                        properties.claim().leaseDuration(),
                        properties.claim().batchSize());
        UUID id = UUID.randomUUID(), token = UUID.randomUUID();
        service.claimJobDefinition(id, now, "owner");
        verify(definitions)
                .claimJobDefinition(id, now, "manual:owner", properties.claim().leaseDuration());
        assertThat(service.releaseClaim(id, token, "test")).isFalse();
        verify(definitions).releaseClaim(id, token, "test");
    }
}
