package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.DependencyCondition;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Evaluator for MIN_ROW_COUNT condition.
 * 
 * <p>Checks if a dataset manifest exists, is READY, and has at least the minimum row count.
 * 
 * <p>Use case: Ensure upstream dataset has sufficient data before processing.
 * Example: Require at least 100 stock symbols before running analysis.
 * 
 * <p>Parameters: Integer (minimum row count threshold)
 */
@Slf4j
public class MinRowCountEvaluator implements ConditionEvaluator {
    
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.MIN_ROW_COUNT;
    }
    
    @Override
    public DependencyCheckResult evaluate(
        DatasetRef datasetRef,
        Object parameters,
        EvaluationContext context
    ) {
        // Validate parameters
        if (!(parameters instanceof Number)) {
            log.error("MIN_ROW_COUNT requires Number parameter, got: {}",
                parameters != null ? parameters.getClass() : "null");
            return DependencyCheckResult.error(datasetRef,
                "Invalid parameter type for MIN_ROW_COUNT: expected Number");
        }
        
        long minRowCount = ((Number) parameters).longValue();
        
        log.debug("Checking MIN_ROW_COUNT for dataset={} partition={}, threshold={}",
            datasetRef.getDataset(), datasetRef.getPartition(), minRowCount);
        
        try {
            Optional<DatasetManifest> manifestOpt = context.readManifest(datasetRef);
            
            if (manifestOpt.isEmpty()) {
                log.debug("Manifest missing for dataset={} partition={}",
                    datasetRef.getDataset(), datasetRef.getPartition());
                return DependencyCheckResult.missing(datasetRef);
            }
            
            DatasetManifest manifest = manifestOpt.get();
            
            // Check READY status first
            if (!manifest.isReady()) {
                log.debug("Manifest not ready for dataset={} partition={}, status={}",
                    datasetRef.getDataset(), datasetRef.getPartition(), manifest.status());
                return DependencyCheckResult.notReady(datasetRef, manifest.status());
            }
            
            // Check row count
            long actualRowCount = manifest.rowCount();
            
            if (actualRowCount < minRowCount) {
                log.debug("Dataset has insufficient rows: dataset={} partition={}, actual={}, required={}",
                    datasetRef.getDataset(), datasetRef.getPartition(), actualRowCount, minRowCount);
                return DependencyCheckResult.empty(datasetRef, actualRowCount, minRowCount);
            }
            
            log.debug("Dataset has sufficient rows: dataset={} partition={}, actual={}, required={}",
                datasetRef.getDataset(), datasetRef.getPartition(), actualRowCount, minRowCount);
            return DependencyCheckResult.ready();
            
        } catch (Exception e) {
            log.error("Error checking MIN_ROW_COUNT for dataset={} partition={}",
                datasetRef.getDataset(), datasetRef.getPartition(), e);
            return DependencyCheckResult.error(datasetRef, "Failed to read manifest: " + e.getMessage());
        }
    }
}
