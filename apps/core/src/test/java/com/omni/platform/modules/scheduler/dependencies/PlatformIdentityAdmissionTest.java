package com.omni.platform.modules.scheduler.dependencies;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness;
import com.omni.platform.modules.scheduler.metadata.OutputWriteAdmission;

class PlatformIdentityAdmissionTest {
    private final JobDependencyGuard guard = mock(JobDependencyGuard.class);
    private final JobDependencyContextFactory factory = mock(JobDependencyContextFactory.class);
    private final ManifestReader metadata = mock(ManifestReader.class);
    private final OutputWorkReadiness identities = mock(OutputWorkReadiness.class);
    private final OutputWriteAdmission writers = mock(OutputWriteAdmission.class);
    private final PlatformDependencyRegistry registry =
            new PlatformDependencyRegistry(guard, factory);
    private final DatasetRef eod = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg"));
    private final UUID execution = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-03T00:00:00Z");
    private final JobDefinition job = new JobDefinition();

    @BeforeEach
    void configure() {
        job.setJobType(JobDefinition.JobType.SYNC_INDICATORS);
        ReflectionTestUtils.setField(registry, "metadataReader", metadata);
        ReflectionTestUtils.setField(registry, "workReadiness", identities);
        ReflectionTestUtils.setField(registry, "outputWriteAdmission", writers);
        when(factory.create(any(), anyString(), anyString(), anyString()))
                .thenReturn(new JobExecutionContext(job, execution.toString(), Map.of()));
        when(guard.checkDependencies(any()))
                .thenReturn(JobDependencyGuard.GuardResult.ready(Map.of(eod, "v1")));
        when(metadata.coversDate(any(), anyString(), any())).thenReturn(true);
        when(identities.bind(eq(execution), anyMap())).thenAnswer(call -> call.getArgument(1));
    }

    private DependencyRequest request(Map<String, Object> meta) {
        return new DependencyRequest(
                UUID.randomUUID(), execution, null, job, "SYMBOL", "HOSE-HPG", "run", meta, now);
    }

    @Test
    void readyDecisionCarriesTheFrozenIdentityInsteadOfOnlyPartitionCoverage() {
        var bound =
                eod.bindWork(
                        "work:" + "a".repeat(64), LocalDate.of(2026, 11, 3), UUID.randomUUID());
        when(identities.bind(eq(execution), anyMap())).thenReturn(Map.of(bound, "v1"));
        var result = registry.evaluate(request(Map.of("workBusinessDate", "2026-11-03")));
        assertThat(result.state()).isEqualTo(DependencyDecision.State.READY);
        assertThat(result.approvedInputVersions()).containsKey(bound).doesNotContainKey(eod);
    }

    @Test
    void partitionReadyCannotBypassMissingOrReplacedUpstreamIdentity() {
        when(identities.bind(eq(execution), anyMap())).thenReturn(null);
        var result = registry.evaluate(request(Map.of("workBusinessDate", "2026-11-03")));
        assertThat(result.reasonCode()).isEqualTo("UPSTREAM_IDENTITY_NOT_READY");
        assertThat(result.retryAt()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void actualDateAndUncertainWriterGuardsStillRunBeforeWorkApproval() {
        when(metadata.coversDate(any(), anyString(), any())).thenReturn(false);
        assertThat(
                        registry.evaluate(request(Map.of("workBusinessDate", "2026-11-03")))
                                .reasonCode())
                .isEqualTo("INPUT_DATE_NOT_COVERED");
        verify(identities, never()).bind(any(), anyMap());
        when(writers.waiting(execution)).thenReturn(true);
        assertThat(registry.evaluate(request(Map.of())).reasonCode())
                .isEqualTo("OUTPUT_WRITE_WAITING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TREND_MOMENTUM_V1", "CONFIRMED_TREND_EQUALS"})
    void signalApprovalsFollowTheDeclaredDirectGraphInputs(String strategy) {
        job.setJobType(JobDefinition.JobType.SYNC_SIGNALS);
        var result =
                registry.evaluate(
                        request(
                                Map.of(
                                        "workBusinessDate",
                                        "2026-11-03",
                                        "workExpansion",
                                        Map.of(
                                                "parameters",
                                                Map.of("strategy", strategy, "timeframe", "1d")))));
        assertThat(result.state()).isEqualTo(DependencyDecision.State.READY);
        var captured = ArgumentCaptor.forClass(JobExecutionContext.class);
        verify(guard).checkDependencies(captured.capture());
        var datasets = captured.getValue().getDependsOnDatasets();
        assertThat(datasets).hasSize(strategy.equals("CONFIRMED_TREND_EQUALS") ? 3 : 2);
        assertThat(datasets.getFirst()).containsEntry("dataset", "eod");
    }

    @Test
    void missingDependencyRemainsWaitingInsteadOfInventingAnIdentity() {
        when(guard.checkDependencies(any()))
                .thenReturn(
                        JobDependencyGuard.GuardResult.blocked(
                                List.of(DependencyCheckResult.missing(eod)), null));
        assertThat(registry.evaluate(request(Map.of())).reasonCode())
                .isEqualTo("DEPENDENCY_WAITING");
        verify(identities, never()).bind(any(), anyMap());
    }

    @Test
    void incompatibleSchemaBlocksBeforeAnyIdentityApproval() {
        when(guard.checkDependencies(any()))
                .thenReturn(
                        JobDependencyGuard.GuardResult.blocked(
                                List.of(DependencyCheckResult.invalidSchema(eod, 2, "1")),
                                "unsupported schema"));
        assertThat(registry.evaluate(request(Map.of())).reasonCode())
                .isEqualTo("DEPENDENCY_INCOMPATIBLE");
        verify(identities, never()).bind(any(), anyMap());
    }

    @Test
    void malformedAcceptedSignalCoordinatesCannotBeUsedAsWorkIdentity() {
        job.setJobType(JobDefinition.JobType.SYNC_SIGNALS);
        assertThatThrownBy(
                        () -> registry.evaluate(request(Map.of("workBusinessDate", "2026-11-03"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                registry.evaluate(
                                        request(
                                                Map.of(
                                                        "workBusinessDate",
                                                        "2026-11-03",
                                                        "workExpansion",
                                                        Map.of(
                                                                "parameters",
                                                                "not accepted parameters")))))
                .isInstanceOf(IllegalArgumentException.class);
        var badKey =
                new DependencyRequest(
                        UUID.randomUUID(),
                        execution,
                        null,
                        job,
                        "SYMBOL",
                        "HPG",
                        "run",
                        Map.of("workBusinessDate", "2026-11-03"),
                        now);
        assertThatThrownBy(() -> registry.evaluate(badKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyAdapterBoundaryDoesNotInventDateCoverage() {
        var legacy = new PlatformDependencyRegistry(guard, factory);
        assertThat(legacy.evaluate(request(Map.of())).state())
                .isEqualTo(DependencyDecision.State.READY);
        assertThat(legacy.evaluate(request(Map.of("workBusinessDate", "2026-11-03"))).reasonCode())
                .isEqualTo("INPUT_DATE_NOT_COVERED");
    }

    @Test
    void enrolledWorkDoesNotReadTheLatestFileVersionOrDate() {
        when(identities.enrolled(execution)).thenReturn(true);
        when(guard.parseDependencies(any()))
                .thenReturn(
                        List.of(
                                new DatasetDependency(
                                        eod,
                                        List.of(
                                                DependencyCondition.EXISTS,
                                                DependencyCondition.READY),
                                        Map.of(),
                                        DatasetDependency.DependencyMode.ENFORCED)));
        var bound =
                eod.bindWork(
                        "work:" + "a".repeat(64), LocalDate.of(2026, 11, 3), UUID.randomUUID());
        when(identities.bind(eq(execution), anyMap())).thenReturn(Map.of(bound, "scoped-version"));
        var result = registry.evaluate(request(Map.of("workBusinessDate", "2026-11-03")));
        assertThat(result.approvedInputVersions()).containsEntry(bound, "scoped-version");
        verify(guard, never()).checkDependencies(any());
        verifyNoInteractions(metadata);
        when(identities.bind(eq(execution), anyMap())).thenReturn(null);
        assertThat(registry.evaluate(request(Map.of())).reasonCode())
                .isEqualTo("UPSTREAM_IDENTITY_NOT_READY");
    }

    @Test
    void enrolledWorkCannotSilentlyIgnoreAnUnadaptedPolicy() {
        when(identities.enrolled(execution)).thenReturn(true);
        when(guard.parseDependencies(any()))
                .thenReturn(
                        List.of(
                                new DatasetDependency(
                                        eod,
                                        List.of(DependencyCondition.CURRENT_INPUTS),
                                        Map.of(),
                                        DatasetDependency.DependencyMode.ENFORCED)));
        assertThat(registry.evaluate(request(Map.of())).reasonCode())
                .isEqualTo("WORK_SCOPE_POLICY_UNSUPPORTED");
        verify(identities, never()).bind(any(), anyMap());
    }
}
