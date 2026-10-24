package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;

class JobServiceClaimTest {
    private static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    private final JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
    private final JobExecutionHistoryRepository histories =
            mock(JobExecutionHistoryRepository.class);
    private final JobService service =
            new JobService(definitions, histories, null, null, null, null, null);

    @Test
    void foreignDefinitionTokenAndOwnerFailBeforePreparationOrLeaseRelease() {
        var job = claimedJob();
        for (int mismatch = 0; mismatch < 3; mismatch++) {
            var claim =
                    new SchedulerClaim(
                            mismatch == 0 ? UUID.randomUUID() : job.getId(),
                            mismatch == 1 ? UUID.randomUUID() : job.getClaimToken(),
                            mismatch == 2 ? "other-instance" : job.getClaimedBy(),
                            NOW,
                            NOW.plusSeconds(60),
                            NOW);
            assertThatThrownBy(() -> service.prepareClaimedExecution(job, claim, NOW))
                    .hasMessageContaining("no longer owns");
            assertThatThrownBy(() -> service.completeRunPreparation(job, claim, NOW, false))
                    .hasMessageContaining("no longer owns");
            assertThatThrownBy(
                            () ->
                                    service.prepareManualClaimedExecution(
                                            job, claim, NOW, Map.of(), Map.of()))
                    .hasMessageContaining("no longer owns");
        }
        verifyNoInteractions(definitions, histories);
    }

    @Test
    void scheduledPreparationAdvancesCadenceAndReleasesOnlyMatchingClaim() {
        var job = claimedJob();
        var claim = claim(job);
        job.setNextRun(NOW.minusSeconds(60));
        ReflectionTestUtils.setField(service, "schedulerZone", "Asia/Ho_Chi_Minh");
        when(definitions.releaseClaim(claim)).thenReturn(true);
        service.completeRunPreparation(job, claim, NOW, false);
        assertThat(job.getNextRun()).isAfter(NOW);
        verify(definitions).saveAndFlush(job);
        verify(definitions).releaseClaim(claim);
        verifyNoInteractions(histories);
    }

    @Test
    void internalManualPreparationPreservesCadenceAndApprovedLogicalInputs() {
        var job = claimedJob();
        var original = NOW.plusSeconds(300);
        job.setNextRun(original);
        when(histories.save(any(JobExecutionHistory.class)))
                .thenAnswer(call -> call.getArgument(0));
        var input = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg"));
        var execution =
                service.prepareManualClaimedExecution(
                        job,
                        claim(job),
                        NOW,
                        Map.of(input, "version-1"),
                        Map.of("reason", "internal compatibility"));
        assertThat(execution.getTriggeredAt()).isEqualTo(NOW);
        assertThat(execution.getMetaJson()).containsEntry("reason", "internal compatibility");
        assertThat(execution.getMetaJson().get("approvedInputs"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        input.getPartition(),
                                        "dataVersion",
                                        "version-1")));
        var empty = service.prepareManualClaimedExecution(job, claim(job), NOW, Map.of(), Map.of());
        assertThat(empty.getMetaJson()).isEmpty();
        when(definitions.releaseClaim(any())).thenReturn(true);
        service.completeRunPreparation(job, claim(job), NOW, true);
        assertThat(job.getNextRun()).isEqualTo(original);
        verify(definitions, never()).saveAndFlush(any());
    }

    @Test
    void lostLeaseReleaseFailsInsteadOfReportingPreparationSuccess() {
        var job = claimedJob();
        var claim = claim(job);
        when(definitions.releaseClaim(claim)).thenReturn(false);
        assertThatThrownBy(() -> service.completeRunPreparation(job, claim, NOW, true))
                .hasMessageContaining("superseded");
        verify(definitions, never()).saveAndFlush(any());
        verifyNoInteractions(histories);
    }

    private static JobDefinition claimedJob() {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setClaimToken(UUID.randomUUID());
        job.setClaimedBy("core-a");
        job.setCronExpr("0 0 * * * *");
        job.setSource(JobDefinition.DataSource.VCI);
        return job;
    }

    private static SchedulerClaim claim(JobDefinition job) {
        return new SchedulerClaim(
                job.getId(),
                job.getClaimToken(),
                job.getClaimedBy(),
                NOW,
                NOW.plusSeconds(60),
                NOW);
    }
}
