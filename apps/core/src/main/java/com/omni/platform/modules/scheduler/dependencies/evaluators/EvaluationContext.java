package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;

import java.util.Map;
import java.util.Optional;

/**
 * Shared context for condition evaluation.
 * 
 * <p>Provides evaluators with:
 * <ul>
 *   <li>Manifest reader for accessing dataset metadata</li>
 *   <li>Upstream dataset versions for CURRENT_INPUTS checks</li>
 *   <li>Job execution context (job name, execution ID)</li>
 * </ul>
 * 
 * <p>Immutable and thread-safe.
 */
public record EvaluationContext(
    ManifestReader manifestReader,
    Map<DatasetRef, String> upstreamVersions,
    String jobName,
    String executionId
) {
    
    /**
     * Read a manifest through the context's reader.
     * 
     * <p>Convenience method that delegates to the ManifestReader.
     */
    public Optional<DatasetManifest> readManifest(DatasetRef datasetRef) {
        return manifestReader.readManifest(datasetRef);
    }
    
    /**
     * Check if a manifest exists without reading full content.
     * 
     * <p>Convenience method for EXISTS checks.
     */
    public boolean manifestExists(DatasetRef datasetRef) {
        return manifestReader.manifestExists(datasetRef);
    }
    
    /**
     * Get the expected upstream version for a dataset.
     * 
     * <p>Used by CURRENT_INPUTS evaluator to compare recorded vs. actual versions.
     * 
     * @return dataVersion string if known, empty if no upstream version tracked
     */
    public Optional<String> getUpstreamVersion(DatasetRef datasetRef) {
        return Optional.ofNullable(upstreamVersions.get(datasetRef));
    }
}
