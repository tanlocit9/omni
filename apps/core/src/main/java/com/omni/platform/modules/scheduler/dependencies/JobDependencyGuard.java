package com.omni.platform.modules.scheduler.dependencies;

import java.util.List;

/**
 * Guard interface for validating dataset dependencies before job execution.
 * 
 * <p>The guard checks if all required datasets are available and up-to-date
 * before allowing a job to be dispatched. Jobs with unmet dependencies are
 * blocked and retried with exponential backoff.
 * 
 * <p>Core workflow:
 * <ol>
 *   <li>Parse dataset dependencies from job config</li>
 *   <li>For each dependency, evaluate all conditions (EXISTS, READY, CURRENT_INPUTS, etc.)</li>
 *   <li>If all dependencies satisfied: return READY, allow execution</li>
 *   <li>If any ENFORCED dependency unsatisfied: return BLOCKED results, defer execution</li>
 *   <li>If only DOCUMENTATION_ONLY dependencies unsatisfied: log warnings, allow execution</li>
 * </ol>
 * 
 * <p>Thread-safety: Implementations must be thread-safe for concurrent scheduler access.
 */
public interface JobDependencyGuard {
    
    /**
     * Result of dependency validation.
     */
    record GuardResult(
        boolean canExecute,
        List<DependencyCheckResult> checks,
        String blockReason
    ) {
        /**
         * All dependencies satisfied, job can execute.
         */
        public static GuardResult ready() {
            return new GuardResult(true, List.of(), null);
        }
        
        /**
         * Some ENFORCED dependencies not satisfied, job must be blocked.
         */
        public static GuardResult blocked(List<DependencyCheckResult> failedChecks, String reason) {
            return new GuardResult(false, failedChecks, reason);
        }
        
        /**
         * Only DOCUMENTATION_ONLY dependencies failed, job can proceed with warnings.
         */
        public static GuardResult readyWithWarnings(List<DependencyCheckResult> warnings) {
            return new GuardResult(true, warnings, null);
        }
        
        /**
         * Check if job is blocked (cannot execute).
         */
        public boolean isBlocked() {
            return !canExecute;
        }
        
        /**
         * Check if there are warnings (DOCUMENTATION_ONLY failures).
         */
        public boolean hasWarnings() {
            return canExecute && !checks.isEmpty();
        }
    }
    
    /**
     * Validate all dataset dependencies for a job execution.
     * 
     * <p>Reads dataset dependencies from job config, evaluates each condition,
     * and returns a result indicating whether the job can execute.
     * 
     * @param context job execution context with definition, execution ID, upstream versions
     * @return guard result indicating if job can execute and which dependencies failed
     */
    GuardResult checkDependencies(JobExecutionContext context);
    
    /**
     * Parse dataset dependencies from job config.
     * 
     * <p>Helper method for extracting and validating dependency declarations.
     * 
     * @param context job execution context
     * @return list of parsed dataset dependencies
     */
    List<DatasetDependency> parseDependencies(JobExecutionContext context);
}
