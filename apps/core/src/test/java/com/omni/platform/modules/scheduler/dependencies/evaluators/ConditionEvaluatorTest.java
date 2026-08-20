package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.DependencyStatus;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for condition evaluators: EXISTS, READY, MIN_ROW_COUNT.
 */
class ConditionEvaluatorTest {

    private ManifestReader manifestReader;
    private EvaluationContext context;
    private DatasetRef ref;

    @BeforeEach
    void setUp() {
        manifestReader = mock(ManifestReader.class);
        context = new EvaluationContext(manifestReader, Map.of(), "test-job", "exec-001");
        ref = DatasetRef.of("eod", Map.of("exchange", "hose"));
    }

    // -------------------------------------------------------------------------
    // ExistsEvaluator
    // -------------------------------------------------------------------------

    @Test
    void existsEvaluator_returnsReady_whenManifestExists() {
        when(manifestReader.manifestExists(ref)).thenReturn(true);
        ExistsEvaluator evaluator = new ExistsEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.READY);
    }

    @Test
    void existsEvaluator_returnsMissing_whenManifestAbsent() {
        when(manifestReader.manifestExists(ref)).thenReturn(false);
        ExistsEvaluator evaluator = new ExistsEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.MISSING);
        assertThat(result.getDatasetRef()).isEqualTo(ref);
        assertThat(result.getReason().orElse("")).contains("eod");
    }

    @Test
    void existsEvaluator_returnsError_onException() {
        when(manifestReader.manifestExists(ref)).thenThrow(new RuntimeException("Connection refused"));
        ExistsEvaluator evaluator = new ExistsEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.ERROR);
        assertThat(result.getReason().orElse("")).contains("Connection refused");
    }

    // -------------------------------------------------------------------------
    // ReadyEvaluator
    // -------------------------------------------------------------------------

    @Test
    void readyEvaluator_returnsReady_whenManifestStatusIsReady() {
        DatasetManifest manifest = readyManifest();
        when(manifestReader.readManifest(ref)).thenReturn(Optional.of(manifest));
        ReadyEvaluator evaluator = new ReadyEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.READY);
    }

    @Test
    void readyEvaluator_returnsMissing_whenManifestAbsent() {
        when(manifestReader.readManifest(ref)).thenReturn(Optional.empty());
        ReadyEvaluator evaluator = new ReadyEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.MISSING);
    }

    @Test
    void readyEvaluator_returnsNotReady_whenStatusIsProcessing() {
        DatasetManifest manifest = manifestWithStatus("PROCESSING");
        when(manifestReader.readManifest(ref)).thenReturn(Optional.of(manifest));
        ReadyEvaluator evaluator = new ReadyEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.NOT_READY);
        assertThat(result.getReason().orElse("")).contains("PROCESSING");
    }

    @Test
    void readyEvaluator_returnsNotReady_whenStatusIsFailed() {
        DatasetManifest manifest = manifestWithStatus("FAILED");
        when(manifestReader.readManifest(ref)).thenReturn(Optional.of(manifest));
        ReadyEvaluator evaluator = new ReadyEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, null, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.NOT_READY);
        assertThat(result.getReason().orElse("")).contains("FAILED");
    }

    // -------------------------------------------------------------------------
    // MinRowCountEvaluator
    // -------------------------------------------------------------------------

    @Test
    void minRowCountEvaluator_returnsReady_whenRowCountAboveMinimum() {
        DatasetManifest manifest = manifestWithRowCount(500L);
        when(manifestReader.readManifest(ref)).thenReturn(Optional.of(manifest));
        MinRowCountEvaluator evaluator = new MinRowCountEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, 100L, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.READY);
    }

    @Test
    void minRowCountEvaluator_returnsEmpty_whenRowCountBelowMinimum() {
        DatasetManifest manifest = manifestWithRowCount(5L);
        when(manifestReader.readManifest(ref)).thenReturn(Optional.of(manifest));
        MinRowCountEvaluator evaluator = new MinRowCountEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, 100L, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.EMPTY);
        assertThat(result.getReason().orElse("")).contains("5");
        assertThat(result.getReason().orElse("")).contains("100");
    }

    @Test
    void minRowCountEvaluator_returnsMissing_whenManifestAbsent() {
        when(manifestReader.readManifest(ref)).thenReturn(Optional.empty());
        MinRowCountEvaluator evaluator = new MinRowCountEvaluator();

        DependencyCheckResult result = evaluator.evaluate(ref, 100L, context);

        assertThat(result.getStatus()).isEqualTo(DependencyStatus.MISSING);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DatasetManifest readyManifest() {
        return new DatasetManifest(
            1, "eod", Map.of("exchange", "hose"), "READY",
            "sha256:abc123", "eod/hose/data.parquet",
            1, 4096L, 1000L, 5, List.of(), // columns
            1, "schema-hash-abc",
            null, null, List.of(), // inputs
            null, "2024-01-15T10:00:00Z"
        );
    }

    private DatasetManifest manifestWithStatus(String status) {
        return new DatasetManifest(
            1, "eod", Map.of("exchange", "hose"), status,
            "sha256:dummy", "eod/hose/data.parquet",
            0, 0L, 0L, 0, List.of(), // columns
            1, "schema-hash-dummy",
            null, null, List.of(), // inputs
            null, "2024-01-15T10:00:00Z"
        );
    }

    private DatasetManifest manifestWithRowCount(long rowCount) {
        return new DatasetManifest(
            1, "eod", Map.of("exchange", "hose"), "READY",
            "sha256:def456", "eod/hose/data.parquet",
            1, 4096L, rowCount, 5, List.of(), // columns
            1, "schema-hash-def",
            null, null, List.of(), // inputs
            null, "2024-01-15T10:00:00Z"
        );
    }
}
