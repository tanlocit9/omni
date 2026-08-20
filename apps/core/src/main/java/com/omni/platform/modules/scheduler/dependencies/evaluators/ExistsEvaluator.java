package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.DependencyCondition;
import lombok.extern.slf4j.Slf4j;

/**
 * Evaluator for EXISTS condition.
 * 
 * <p>Checks if a dataset manifest file exists in object storage.
 * Does not require the manifest to be READY, just present.
 * 
 * <p>Use case: Verify a dataset has been initialized before proceeding.
 * 
 * <p>Parameters: None
 */
@Slf4j
public class ExistsEvaluator implements ConditionEvaluator {
    
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.EXISTS;
    }
    
    @Override
    public DependencyCheckResult evaluate(
        DatasetRef datasetRef,
        Object parameters,
        EvaluationContext context
    ) {
        log.debug("Checking EXISTS for dataset={} partition={}",
            datasetRef.getDataset(), datasetRef.getPartition());
        
        try {
            boolean exists = context.manifestExists(datasetRef);
            
            if (exists) {
                log.debug("Manifest exists for dataset={} partition={}",
                    datasetRef.getDataset(), datasetRef.getPartition());
                return DependencyCheckResult.ready();
            } else {
                log.debug("Manifest missing for dataset={} partition={}",
                    datasetRef.getDataset(), datasetRef.getPartition());
                return DependencyCheckResult.missing(datasetRef);
            }
            
        } catch (Exception e) {
            log.error("Error checking manifest existence for dataset={} partition={}",
                datasetRef.getDataset(), datasetRef.getPartition(), e);
            return DependencyCheckResult.error(datasetRef, "Failed to check manifest: " + e.getMessage());
        }
    }
}
