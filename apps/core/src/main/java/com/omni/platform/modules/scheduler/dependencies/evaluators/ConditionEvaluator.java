package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.DependencyCondition;

/**
 * Strategy interface for evaluating specific dependency conditions.
 * 
 * <p>Each implementation handles one type of condition check (EXISTS, READY, CURRENT_INPUTS, etc.)
 * and returns a {@link DependencyCheckResult} indicating whether the condition is satisfied.
 * 
 * <p>Evaluators are stateless and thread-safe. They receive the ManifestReader and any
 * required parameters through method arguments.
 * 
 * <p>Implementation examples:
 * <ul>
 *   <li>{@code ExistsEvaluator}: Checks if manifest file exists</li>
 *   <li>{@code ReadyEvaluator}: Checks if manifest status is READY</li>
 *   <li>{@code MinRowCountEvaluator}: Checks if rowCount >= threshold</li>
 *   <li>{@code CurrentInputsEvaluator}: Checks upstream dataVersion matching</li>
 * </ul>
 */
public interface ConditionEvaluator {
    
    /**
     * Get the condition type this evaluator handles.
     */
    DependencyCondition getCondition();
    
    /**
     * Evaluate the condition for the given dataset reference.
     * 
     * @param datasetRef the dataset partition to check
     * @param parameters condition-specific parameters (e.g., minRowCount, maxFreshnessLag)
     * @param context evaluation context providing manifest access and upstream info
     * @return check result indicating if condition is satisfied
     */
    DependencyCheckResult evaluate(
        DatasetRef datasetRef,
        Object parameters,
        EvaluationContext context
    );
}
