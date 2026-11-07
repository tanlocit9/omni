package com.omni.platform.modules.scheduler.dependencies;

import com.omni.platform.modules.scheduler.dependencies.evaluators.EvaluationContext;
import com.omni.platform.modules.scheduler.dependencies.evaluators.ExistsEvaluator;
import com.omni.platform.modules.scheduler.dependencies.evaluators.ReadyEvaluator;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultJobDependencyGuard}.
 *
 * <p>Tests guard logic: dependency parsing, evaluator dispatch, ENFORCED vs
 * DOCUMENTATION_ONLY failure separation, and GuardResult semantics.
 */
class DefaultJobDependencyGuardTest {

    private ManifestReader manifestReader;
    private DefaultJobDependencyGuard guard;

    @BeforeEach
    void setUp() {
        manifestReader = mock(ManifestReader.class);
        guard = new DefaultJobDependencyGuard(manifestReader);
    }

    @Test
    void checksReady_whenNoDependenciesConfigured() {
        JobExecutionContext context = contextWithConfig(Map.of());
        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isTrue();
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.checks()).isEmpty();
    }

    @Test
    void checksReady_whenAllEnforcedDependenciesSatisfied() {
        DatasetRef eodRef = DatasetRef.of("eod", Map.of("exchange", "hose"));
        DatasetManifest readyManifest = readyManifest("eod");

        when(manifestReader.readManifest(eodRef)).thenReturn(Optional.of(readyManifest));
        when(manifestReader.manifestExists(eodRef)).thenReturn(true);

        JobExecutionContext context =
                contextWithDeps(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        Map.of("exchange", "hose"),
                                        "conditions",
                                        List.of("EXISTS", "READY"),
                                        "mode",
                                        "ENFORCED")));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isTrue();
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.approvedInputVersions())
                .containsExactly(Map.entry(eodRef, "sha256:abc123"));
    }

    @Test
    void blocks_whenEnforcedDependencyMissing() {
        DatasetRef missingRef = DatasetRef.of("eod", Map.of("exchange", "hose"));
        when(manifestReader.manifestExists(missingRef)).thenReturn(false);
        when(manifestReader.readManifest(missingRef)).thenReturn(Optional.empty());

        JobExecutionContext context =
                contextWithDeps(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        Map.of("exchange", "hose"),
                                        "conditions",
                                        List.of("EXISTS"),
                                        "mode",
                                        "ENFORCED")));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isFalse();
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.approvedInputVersions()).isEmpty();
        assertThat(result.blockReason()).isNotEmpty();
        assertThat(result.checks()).hasSize(1);
        assertThat(result.checks().get(0).getStatus()).isEqualTo(DependencyStatus.MISSING);
    }

    @Test
    void blocks_whenEnforcedDependencyNotReady() {
        DatasetRef ref = DatasetRef.of("eod", Map.of("exchange", "hose"));
        DatasetManifest processingManifest = processingManifest("eod");

        when(manifestReader.manifestExists(ref)).thenReturn(true);
        when(manifestReader.readManifest(ref)).thenReturn(Optional.of(processingManifest));

        JobExecutionContext context =
                contextWithDeps(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        Map.of("exchange", "hose"),
                                        "conditions",
                                        List.of("READY"),
                                        "mode",
                                        "ENFORCED")));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isFalse();
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.checks().get(0).getStatus()).isEqualTo(DependencyStatus.NOT_READY);
    }

    @Test
    void allowsExecution_withWarningsOnly_whenDocumentationOnlyDependencyFails() {
        DatasetRef ref = DatasetRef.of("symbols", Map.of());
        when(manifestReader.manifestExists(ref)).thenReturn(false);
        when(manifestReader.readManifest(ref)).thenReturn(Optional.empty());

        JobExecutionContext context =
                contextWithDeps(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "symbols",
                                        "partition",
                                        Map.of(),
                                        "conditions",
                                        List.of("EXISTS"),
                                        "mode",
                                        "DOCUMENTATION_ONLY")));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isTrue();
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.checks()).hasSize(1);
        assertThat(result.checks().get(0).getStatus()).isEqualTo(DependencyStatus.MISSING);
    }

    @Test
    void blocks_onlyOnEnforcedFailures_whenMixedModes() {
        DatasetRef enforced = DatasetRef.of("eod", Map.of("exchange", "hose"));
        DatasetRef docOnly = DatasetRef.of("symbols", Map.of());

        when(manifestReader.manifestExists(enforced)).thenReturn(false);
        when(manifestReader.readManifest(enforced)).thenReturn(Optional.empty());
        when(manifestReader.manifestExists(docOnly)).thenReturn(false);
        when(manifestReader.readManifest(docOnly)).thenReturn(Optional.empty());

        JobExecutionContext context =
                contextWithDeps(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        Map.of("exchange", "hose"),
                                        "conditions",
                                        List.of("EXISTS"),
                                        "mode",
                                        "ENFORCED"),
                                Map.of(
                                        "dataset",
                                        "symbols",
                                        "partition",
                                        Map.of(),
                                        "conditions",
                                        List.of("EXISTS"),
                                        "mode",
                                        "DOCUMENTATION_ONLY")));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.blockReason()).contains("missing");
    }

    @Test
    void blocksWithoutPartialApprovals_whenAnyEnforcedDependencyFails() {
        DatasetRef readyRef = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg"));
        DatasetRef missingRef = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "vnm"));

        when(manifestReader.readManifest(readyRef)).thenReturn(Optional.of(readyManifest("eod")));
        when(manifestReader.readManifest(missingRef)).thenReturn(Optional.empty());

        JobExecutionContext context =
                contextWithDeps(
                        List.of(
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        Map.of("exchange", "hose", "code", "hpg"),
                                        "conditions",
                                        List.of("READY"),
                                        "mode",
                                        "ENFORCED"),
                                Map.of(
                                        "dataset",
                                        "eod",
                                        "partition",
                                        Map.of("exchange", "hose", "code", "vnm"),
                                        "conditions",
                                        List.of("READY"),
                                        "mode",
                                        "ENFORCED")));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.approvedInputVersions()).isEmpty();
    }

    @Test
    void parseDependencies_returnsEmptyList_whenNoDependsOnDatasets() {
        JobExecutionContext context = contextWithConfig(Map.of());
        List<DatasetDependency> deps = guard.parseDependencies(context);
        assertThat(deps).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Test
    void malformedEnforcedDependencyCannotDisappearIntoAnEmptyReadyResult() {
        var context = contextWithConfig(Map.of("dependsOnDatasets", List.of(Map.of(
                "dataset", "eod", "mode", "ENFORCED", "conditions", List.of("MISSPELLED_READY")))));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.checkDependencies(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid dataset dependency");
        verifyNoInteractions(manifestReader);
    }

    @Test
    void malformedDependencyContainerCannotBecomeAnEmptyApproval() {
        var context = contextWithConfig(Map.of("dependsOnDatasets", Map.of("dataset", "eod")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> guard.checkDependencies(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a list");
        verifyNoInteractions(manifestReader);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "processing", "null-version", "blank-version", "read-error"})
    void finalApprovalCannotAdmitDisappearedUnreadyOrUnversionedInput(String state) {
        var ref = DatasetRef.of("eod", Map.of());
        when(manifestReader.manifestExists(ref)).thenReturn(true);
        var manifest = mock(DatasetManifest.class);
        when(manifest.isReady()).thenReturn(!state.equals("processing"));
        when(manifest.dataVersion()).thenReturn(state.equals("blank-version") ? " " : null);
        if (state.equals("read-error"))
            when(manifestReader.readManifest(ref)).thenThrow(new IllegalStateException("read failed"));
        else
            when(manifestReader.readManifest(ref)).thenReturn(
                    state.equals("missing") ? Optional.empty() : Optional.of(manifest));
        var result = guard.checkDependencies(contextWithDeps(List.of(Map.of(
                "dataset", "eod", "conditions", List.of("EXISTS"), "mode", "ENFORCED"))));
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.approvedInputVersions()).isEmpty();
    }

    @Test
    void documentationOnlySuccessDoesNotGrantAnInputApproval() {
        when(manifestReader.manifestExists(any())).thenReturn(true);
        var result = guard.checkDependencies(contextWithDeps(List.of(Map.of(
                "dataset", "eod", "conditions", List.of("EXISTS"), "mode", "DOCUMENTATION_ONLY"))));
        assertThat(result.canExecute()).isTrue();
        assertThat(result.approvedInputVersions()).isEmpty();
        verify(manifestReader, never()).readManifest(any());
    }

    @Test
    void unsupportedPolicyAndInsufficientRowsCannotGrantApproval() {
        when(manifestReader.readManifest(any())).thenReturn(Optional.of(readyManifest("eod")));
        for (var policy : List.of(
                Map.<String, Object>of("dataset", "eod", "conditions", List.of("PARTITION_MATCH"), "mode", "ENFORCED"),
                Map.<String, Object>of("dataset", "eod", "conditions", List.of("MIN_ROW_COUNT"), "minRowCount", 1001, "mode", "ENFORCED"),
                Map.<String, Object>of("dataset", "eod", "conditions", List.of("CURRENT_INPUTS"), "upstreamDatasets", List.of(DatasetRef.of("indicators", Map.of())), "mode", "ENFORCED"))) {
            var result = guard.checkDependencies(contextWithDeps(List.of(policy)));
            assertThat(result.isBlocked()).isTrue();
            assertThat(result.approvedInputVersions()).isEmpty();
        }
    }

    private JobExecutionContext contextWithConfig(Map<String, Object> config) {
        com.omni.platform.modules.scheduler.entities.JobDefinition job =
                mock(com.omni.platform.modules.scheduler.entities.JobDefinition.class);
        when(job.getConfigJson()).thenReturn(config);
        when(job.getJobType())
                .thenReturn(
                        com.omni.platform.modules.scheduler.entities.JobDefinition.JobType
                                .SYNC_INDICATORS);
        when(job.getSource())
                .thenReturn(
                        com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource.VCI);
        return new JobExecutionContext(job, "test-exec-id", Map.of());
    }

    private JobExecutionContext contextWithDeps(java.util.List<Map<String, Object>> deps) {
        return contextWithConfig(Map.of("dependsOnDatasets", deps));
    }

    private DatasetManifest readyManifest(String dataset) {
        return new DatasetManifest(
                1,
                dataset,
                Map.of(),
                "READY",
                "sha256:abc123",
                "path/to/data.parquet",
                1,
                4096L,
                1000L,
                5,
                List.of(),
                1,
                "schema-hash-xyz",
                null,
                null,
                null,
                null,
                "2024-01-15T10:00:00Z");
    }

    private DatasetManifest processingManifest(String dataset) {
        return new DatasetManifest(
                1,
                dataset,
                Map.of(),
                "PROCESSING",
                null,
                "path/to/data.parquet",
                0,
                0L,
                0L,
                0,
                List.of(),
                1,
                null,
                null,
                null,
                null,
                null,
                "2024-01-15T10:00:00Z");
    }
}
