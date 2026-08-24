package com.omni.platform.modules.scheduler.dependencies;

import com.omni.platform.modules.scheduler.entities.JobDefinition;

import java.util.List;
import java.util.Map;

/**
 * Execution context for a job about to be dispatched.
 *
 * <p>Provides the guard with information needed to evaluate dataset dependencies:
 * <ul>
 *   <li>Job definition (type, source, config with dataset dependencies)</li>
 *   <li>Execution ID for traceability</li>
 *   <li>Upstream dataset versions (for CURRENT_INPUTS checks)</li>
 * </ul>
 *
 * <p>Immutable and thread-safe.
 */
public record JobExecutionContext(
    JobDefinition jobDefinition,
    String executionId,
    Map<DatasetRef, String> upstreamVersions,
    List<Map<String, Object>> runtimeDependencies
) {

    public JobExecutionContext(
            JobDefinition jobDefinition,
            String executionId,
            Map<DatasetRef, String> upstreamVersions) {
        this(jobDefinition, executionId, upstreamVersions, List.of());
    }

    public JobExecutionContext {
        upstreamVersions = Map.copyOf(upstreamVersions);
        runtimeDependencies = List.copyOf(runtimeDependencies);
    }

    /**
     * Human-readable job identifier: jobType_source.
     * Used for logging and blocked job tracking.
     */
    public String getJobName() {
        return jobDefinition.getJobType().name() + "_" + jobDefinition.getSource().name();
    }

    /**
     * Job type name for logging.
     */
    public String getJobType() {
        return jobDefinition.getJobType().name();
    }

    /**
     * Get dataset dependencies from job config.
     *
     * <p>Reads the "dependsOnDatasets" key from job config JSON.
     * Expected format:
     * <pre>
     * {
     *   "dependsOnDatasets": [
     *     {"dataset": "eod", "partition": {"exchange": "hose"}, "conditions": ["READY"]},
     *     {"dataset": "symbols", "partition": {}, "conditions": ["EXISTS", "MIN_ROW_COUNT"], "minRowCount": 10}
     *   ]
     * }
     * </pre>
     *
     * @return runtime-expanded dependencies, configured dependencies, or an empty list
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getDependsOnDatasets() {
        if (!runtimeDependencies.isEmpty()) {
            return runtimeDependencies;
        }
        if (jobDefinition.getConfigJson() == null) {
            return List.of();
        }
        Object deps = jobDefinition.getConfigJson().get("dependsOnDatasets");
        if (deps instanceof List<?>) {
            return (List<Map<String, Object>>) deps;
        }
        return List.of();
    }
}
