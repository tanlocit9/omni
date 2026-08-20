package com.omni.platform.modules.scheduler.dependencies;

import java.util.List;
import java.util.Map;

/**
 * Represents a single dataset dependency declaration from job config.
 * 
 * <p>Parsed from job config JSON like:
 * <pre>
 * {
 *   "dataset": "eod",
 *   "partition": {"exchange": "hose"},
 *   "conditions": ["READY", "MIN_ROW_COUNT"],
 *   "minRowCount": 100,
 *   "mode": "ENFORCED"
 * }
 * </pre>
 * 
 * <p>Immutable value object.
 */
public record DatasetDependency(
    DatasetRef datasetRef,
    List<DependencyCondition> conditions,
    Map<String, Object> parameters,
    DependencyMode mode
) {
    
    /**
     * Dependency enforcement mode.
     */
    public enum DependencyMode {
        /**
         * Document-only mode: Check dependency but don't block execution.
         * Log warnings for unmet dependencies but allow job to proceed.
         * Use during migration phase.
         */
        DOCUMENTATION_ONLY,
        
        /**
         * Enforced mode: Block execution if dependency is not satisfied.
         * Job enters BLOCKED state and is retried with exponential backoff.
         * Use for production-critical dependencies.
         */
        ENFORCED
    }
    
    /**
     * Parse a dependency from job config map.
     * 
     * <p>Expected structure:
     * <pre>
     * {
     *   "dataset": "eod",
     *   "partition": {"exchange": "hose"},  // optional, defaults to {}
     *   "conditions": ["READY"],
     *   "mode": "ENFORCED",                 // optional, defaults to DOCUMENTATION_ONLY
     *   // condition-specific parameters:
     *   "minRowCount": 100,
     *   "maxFreshnessLag": 3600,
     *   "upstreamDatasets": [...]
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    public static DatasetDependency fromConfig(Map<String, Object> config) {
        // Required: dataset name
        String dataset = (String) config.get("dataset");
        if (dataset == null) {
            throw new IllegalArgumentException("Dataset dependency missing 'dataset' field");
        }
        
        // Optional: partition (defaults to empty)
        Map<String, String> partition = (Map<String, String>) config.getOrDefault("partition", Map.of());
        DatasetRef datasetRef = DatasetRef.of(dataset, partition);
        
        // Required: conditions
        List<String> conditionNames = (List<String>) config.get("conditions");
        if (conditionNames == null || conditionNames.isEmpty()) {
            throw new IllegalArgumentException("Dataset dependency missing 'conditions' field");
        }
        
        List<DependencyCondition> conditions = conditionNames.stream()
            .map(DependencyCondition::valueOf)
            .toList();
        
        // Optional: mode (defaults to DOCUMENTATION_ONLY for safe migration)
        String modeName = (String) config.getOrDefault("mode", "DOCUMENTATION_ONLY");
        DependencyMode mode = DependencyMode.valueOf(modeName);
        
        // Parameters: pass through all config keys for condition-specific params
        Map<String, Object> parameters = Map.copyOf(config);
        
        return new DatasetDependency(datasetRef, conditions, parameters, mode);
    }
    
    /**
     * Check if this dependency should block execution when not satisfied.
     */
    public boolean isEnforced() {
        return mode == DependencyMode.ENFORCED;
    }
    
    /**
     * Check if this dependency is documentation-only (log warnings but don't block).
     */
    public boolean isDocumentationOnly() {
        return mode == DependencyMode.DOCUMENTATION_ONLY;
    }
    
    /**
     * Get a condition-specific parameter.
     * 
     * <p>Examples:
     * - minRowCount for MIN_ROW_COUNT condition
     * - maxFreshnessLag for MAX_FRESHNESS_LAG condition
     * - upstreamDatasets for CURRENT_INPUTS condition
     */
    public Object getParameter(String key) {
        return parameters.get(key);
    }
}
