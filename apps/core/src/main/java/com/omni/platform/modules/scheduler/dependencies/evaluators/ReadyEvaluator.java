package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.DependencyCondition;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Evaluator for READY condition.
 * 
 * <p>Checks if a dataset manifest exists and has status="READY".
 * A READY manifest guarantees the underlying Parquet data is valid and complete.
 * 
 * <p>Use case: Verify upstream dataset is ready for consumption before processing.
 * 
 * <p>Parameters: None
 */
@Slf4j
public class ReadyEvaluator implements ConditionEvaluator {
    
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.READY;
    }
    
    @Override
    public DependencyCheckResult evaluate(
        DatasetRef datasetRef,
        Object parameters,
        EvaluationContext context
    ) {
        log.debug("Checking READY for dataset={} partition={}",
            datasetRef.getDataset(), datasetRef.getPartition());
        
        try {
            Optional<DatasetManifest> manifestOpt = context.readManifest(datasetRef);
            
            if (manifestOpt.isEmpty()) {
                log.debug("Manifest missing for dataset={} partition={}",
                    datasetRef.getDataset(), datasetRef.getPartition());
                return DependencyCheckResult.missing(datasetRef);
            }
            
            DatasetManifest manifest = manifestOpt.get();
            
            if (manifest.isReady()) {
                log.debug("Manifest READY for dataset={} partition={} dataVersion={}",
                    datasetRef.getDataset(), datasetRef.getPartition(), manifest.dataVersion());
                return DependencyCheckResult.ready();
            } else {
                log.debug("Manifest not ready for dataset={} partition={}, status={}",
                    datasetRef.getDataset(), datasetRef.getPartition(), manifest.status());
                return DependencyCheckResult.notReady(datasetRef, manifest.status());
            }
            
        } catch (Exception e) {
            log.error("Error checking READY status for dataset={} partition={}",
                datasetRef.getDataset(), datasetRef.getPartition(), e);
            return DependencyCheckResult.error(datasetRef, "Failed to read manifest: " + e.getMessage());
        }
    }
}
