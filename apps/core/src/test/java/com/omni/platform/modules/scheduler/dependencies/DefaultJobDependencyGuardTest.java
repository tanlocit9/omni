package com.omni.platform.modules.scheduler.dependencies;

import com.omni.platform.modules.scheduler.dependencies.evaluators.EvaluationContext;
import com.omni.platform.modules.scheduler.dependencies.evaluators.ExistsEvaluator;
import com.omni.platform.modules.scheduler.dependencies.evaluators.ReadyEvaluator;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        JobExecutionContext context = contextWithDeps(List.of(
            Map.of(
                "dataset", "eod",
                "partition", Map.of("exchange", "hose"),
                "conditions", List.of("EXISTS", "READY"),
                "mode", "ENFORCED"
            )
        ));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isTrue();
        assertThat(result.isBlocked()).isFalse();
    }

    @Test
    void blocks_whenEnforcedDependencyMissing() {
        DatasetRef missingRef = DatasetRef.of("eod", Map.of("exchange", "hose"));
        when(manifestReader.manifestExists(missingRef)).thenReturn(false);
        when(manifestReader.readManifest(missingRef)).thenReturn(Optional.empty());

        JobExecutionContext context = contextWithDeps(List.of(
            Map.of(
                "dataset", "eod",
                "partition", Map.of("exchange", "hose"),
                "conditions", List.of("EXISTS"),
                "mode", "ENFORCED"
            )
        ));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.canExecute()).isFalse();
        assertThat(result.isBlocked()).isTrue();
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

        JobExecutionContext context = contextWithDeps(List.of(
            Map.of(
                "dataset", "eod",
                "partition", Map.of("exchange", "hose"),
                "conditions", List.of("READY"),
                "mode", "ENFORCED"
            )
        ));

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

        JobExecutionContext context = contextWithDeps(List.of(
            Map.of(
                "dataset", "symbols",
                "partition", Map.of(),
                "conditions", List.of("EXISTS"),
                "mode", "DOCUMENTATION_ONLY"
            )
        ));

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

        JobExecutionContext context = contextWithDeps(List.of(
            Map.of(
                "dataset", "eod",
                "partition", Map.of("exchange", "hose"),
                "conditions", List.of("EXISTS"),
                "mode", "ENFORCED"
            ),
            Map.of(
                "dataset", "symbols",
                "partition", Map.of(),
                "conditions", List.of("EXISTS"),
                "mode", "DOCUMENTATION_ONLY"
            )
        ));

        JobDependencyGuard.GuardResult result = guard.checkDependencies(context);

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.blockReason()).contains("missing");
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

    private JobExecutionContext contextWithConfig(Map<String, Object> config) {
        com.omni.platform.modules.scheduler.entities.JobDefinition job =
            mock(com.omni.platform.modules.scheduler.entities.JobDefinition.class);
        when(job.getConfigJson()).thenReturn(config);
        when(job.getJobType()).thenReturn(
            com.omni.platform.modules.scheduler.entities.JobDefinition.JobType.SYNC_INDICATORS);
        when(job.getSource()).thenReturn(
            com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource.VCI);
        return new JobExecutionContext(job, "test-exec-id", Map.of());
    }

    private JobExecutionContext contextWithDeps(java.util.List<Map<String, Object>> deps) {
        return contextWithConfig(Map.of("dependsOnDatasets", deps));
    }

    private DatasetManifest readyManifest(String dataset) {
        return new DatasetManifest(
            1, dataset, Map.of(), "READY",
            "sha256:abc123", "path/to/data.parquet",
            1, 4096L, 1000L, 5, List.of(),
            1, "schema-hash-xyz",
            null, null, null, null,
            "2024-01-15T10:00:00Z"
        );
    }

    private DatasetManifest processingManifest(String dataset) {
        return new DatasetManifest(
            1, dataset, Map.of(), "PROCESSING",
            null, "path/to/data.parquet",
            0, 0L, 0L, 0, List.of(),
            1, null,
            null, null, null, null,
            "2024-01-15T10:00:00Z"
        );
    }
}
