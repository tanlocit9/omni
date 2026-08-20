package com.omni.platform.modules.scheduler.dependencies.evaluators;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.DependencyCheckResult;
import com.omni.platform.modules.scheduler.dependencies.DependencyCondition;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetInput;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Evaluator for CURRENT_INPUTS condition.
 * 
 * <p>Checks if a downstream dataset's recorded upstream input versions match
 * the current versions of those upstream datasets. This ensures the downstream
 * dataset reflects the latest upstream data.
 * 
 * <p>Algorithm:
 * 1. Read downstream manifest and extract inputs[].dataVersion
 * 2. For each upstream dataset, read its current manifest.dataVersion
 * 3. If any upstream version differs from recorded version, return INPUT_VERSION_MISMATCH
 * 4. If all versions match, return READY
 * 
 * <p>Use case: Ensure indicators dataset reflects the latest EOD prices before
 * running signals analysis.
 * 
 * <p>Parameters: List<DatasetRef> (expected upstream datasets to validate)
 */
@Slf4j
public class CurrentInputsEvaluator implements ConditionEvaluator {
    
    @Override
    public DependencyCondition getCondition() {
        return DependencyCondition.CURRENT_INPUTS;
    }
    
    @Override
    public DependencyCheckResult evaluate(
        DatasetRef datasetRef,
        Object parameters,
        EvaluationContext context
    ) {
        // Validate parameters
        if (!(parameters instanceof List<?>)) {
            log.error("CURRENT_INPUTS requires List<DatasetRef> parameter, got: {}", 
                parameters != null ? parameters.getClass() : "null");
            return DependencyCheckResult.error(datasetRef, 
                "Invalid parameter type for CURRENT_INPUTS: expected List<DatasetRef>");
        }
        
        @SuppressWarnings("unchecked")
        List<DatasetRef> expectedUpstreams = (List<DatasetRef>) parameters;
        
        log.debug("Checking CURRENT_INPUTS for dataset={} partition={}, expectedUpstreams={}",
            datasetRef.getDataset(), datasetRef.getPartition(), expectedUpstreams.size());
        
        try {
            // Read downstream manifest
            Optional<DatasetManifest> downstreamOpt = context.readManifest(datasetRef);
            
            if (downstreamOpt.isEmpty()) {
                log.debug("Downstream manifest missing for dataset={} partition={}",
                    datasetRef.getDataset(), datasetRef.getPartition());
                return DependencyCheckResult.missing(datasetRef);
            }
            
            DatasetManifest downstream = downstreamOpt.get();
            
            // Check READY status first
            if (!downstream.isReady()) {
                log.debug("Downstream manifest not ready for dataset={} partition={}, status={}",
                    datasetRef.getDataset(), datasetRef.getPartition(), downstream.status());
                return DependencyCheckResult.notReady(datasetRef, downstream.status());
            }
            
            // Check if downstream has lineage tracking
            if (!downstream.hasLineage()) {
                log.warn("Downstream dataset has no inputs[] lineage tracking: dataset={} partition={}",
                    datasetRef.getDataset(), datasetRef.getPartition());
                return DependencyCheckResult.error(datasetRef,
                    "Dataset manifest has no inputs[] for lineage validation");
            }
            
            List<DatasetInput> recordedInputs = downstream.inputs();
            
            // Validate each expected upstream
            for (DatasetRef upstreamRef : expectedUpstreams) {
                // Find recorded input for this upstream
                Optional<DatasetInput> recordedInput = findRecordedInput(recordedInputs, upstreamRef);
                
                if (recordedInput.isEmpty()) {
                    log.warn("Expected upstream not found in recorded inputs: upstream={} downstream={}",
                        upstreamRef, datasetRef);
                    return DependencyCheckResult.error(datasetRef,
                        "Expected upstream not found in manifest inputs: " + upstreamRef.getDataset());
                }
                
                String recordedVersion = recordedInput.get().dataVersion();
                
                // Read current upstream manifest
                Optional<DatasetManifest> upstreamOpt = context.readManifest(upstreamRef);
                
                if (upstreamOpt.isEmpty()) {
                    log.debug("Upstream manifest missing: upstream={}", upstreamRef);
                    return DependencyCheckResult.missing(upstreamRef);
                }
                
                DatasetManifest upstream = upstreamOpt.get();
                
                if (!upstream.isReady()) {
                    log.debug("Upstream not ready: upstream={}, status={}", upstreamRef, upstream.status());
                    return DependencyCheckResult.notReady(upstreamRef, upstream.status());
                }
                
                String currentVersion = upstream.dataVersion();
                
                // Compare versions
                if (!recordedVersion.equals(currentVersion)) {
                    log.info("Upstream version mismatch: upstream={}, recorded={}, current={}, downstream={}", 
                        upstreamRef, recordedVersion, currentVersion, datasetRef);
                    return DependencyCheckResult.inputVersionMismatch(
                        datasetRef, upstreamRef, recordedVersion, currentVersion);
                }
                
                log.debug("Upstream version matches: upstream={}, version={}", upstreamRef, currentVersion);
            }
            
            log.debug("All upstream versions match for downstream dataset={} partition={}",
                datasetRef.getDataset(), datasetRef.getPartition());
            return DependencyCheckResult.ready();
            
        } catch (Exception e) {
            log.error("Error checking CURRENT_INPUTS for dataset={} partition={}",
                datasetRef.getDataset(), datasetRef.getPartition(), e);
            return DependencyCheckResult.error(datasetRef, "Failed to validate inputs: " + e.getMessage());
        }
    }
    
    /**
     * Find the recorded input matching the given upstream reference.
     * 
     * <p>Matches by dataset name and partition keys.
     */
    private Optional<DatasetInput> findRecordedInput(List<DatasetInput> recordedInputs, DatasetRef upstreamRef) {
        return recordedInputs.stream()
            .filter(input -> input.dataset().equals(upstreamRef.getDataset()))
            .filter(input -> input.partition().equals(upstreamRef.getPartition()))
            .findFirst();
    }
}
