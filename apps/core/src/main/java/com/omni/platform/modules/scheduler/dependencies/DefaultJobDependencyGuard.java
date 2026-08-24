package com.omni.platform.modules.scheduler.dependencies;

import com.omni.platform.modules.scheduler.dependencies.evaluators.*;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Default implementation of JobDependencyGuard.
 * 
 * <p>Validates dataset dependencies by:
 * <ol>
 *   <li>Parsing dependency declarations from job config</li>
 *   <li>Evaluating each condition using registered evaluators</li>
 *   <li>Aggregating results and determining if job can execute</li>
 * </ol>
 * 
 * <p>Thread-safe: Uses immutable state and thread-safe ManifestReader.
 */
@Slf4j
@Service
public class DefaultJobDependencyGuard implements JobDependencyGuard {
    
    private final ManifestReader manifestReader;
    private final Map<DependencyCondition, ConditionEvaluator> evaluators;
    
    public DefaultJobDependencyGuard(ManifestReader manifestReader) {
        this.manifestReader = manifestReader;
        this.evaluators = registerEvaluators();
    }
    
    /**
     * Register all condition evaluators.
     */
    private Map<DependencyCondition, ConditionEvaluator> registerEvaluators() {
        List<ConditionEvaluator> evaluatorList = List.of(
            new ExistsEvaluator(),
            new ReadyEvaluator(),
            new MinRowCountEvaluator(),
            new CurrentInputsEvaluator()
            // TODO: Add remaining evaluators:
            // new PartitionMatchEvaluator(),
            // new SupportedSchemaVersionEvaluator(),
            // new MaxFreshnessLagEvaluator()
        );
        
        return evaluatorList.stream()
            .collect(Collectors.toMap(
                ConditionEvaluator::getCondition,
                e -> e
            ));
    }
    
    @Override
    public GuardResult checkDependencies(JobExecutionContext context) {
        log.debug("Checking dependencies for job={} executionId={}", 
            context.getJobName(), context.executionId());
        
        // Parse dependencies from job config
        List<DatasetDependency> dependencies = parseDependencies(context);
        
        if (dependencies.isEmpty()) {
            log.debug("No dataset dependencies configured for job={}", context.getJobName());
            return GuardResult.ready();
        }
        
        log.info("Found {} dataset dependencies for job={}", dependencies.size(), context.getJobName());
        
        // Evaluate each dependency
        List<DependencyCheckResult> allResults = new ArrayList<>();
        List<DependencyCheckResult> enforcedFailures = new ArrayList<>();
        List<DependencyCheckResult> documentationOnlyFailures = new ArrayList<>();
        Map<DatasetRef, String> approvedInputVersions = new LinkedHashMap<>();
        
        for (DatasetDependency dependency : dependencies) {
            List<DependencyCheckResult> depResults = evaluateDependency(dependency, context);
            allResults.addAll(depResults);
            
            // Separate enforced failures from documentation-only failures
            List<DependencyCheckResult> failures = depResults.stream()
                .filter(r -> r.getStatus() != DependencyStatus.READY)
                .toList();
            
            if (!failures.isEmpty()) {
                if (dependency.isEnforced()) {
                    enforcedFailures.addAll(failures);
                    log.warn("ENFORCED dependency not satisfied: dataset={} partition={} failures={}",
                        dependency.datasetRef().getDataset(),
                        dependency.datasetRef().getPartition(),
                        failures.size());
                } else {
                    documentationOnlyFailures.addAll(failures);
                    log.warn("DOCUMENTATION_ONLY dependency not satisfied: dataset={} partition={} failures={}",
                        dependency.datasetRef().getDataset(),
                        dependency.datasetRef().getPartition(),
                        failures.size());
                }
            } else if (dependency.isEnforced()) {
                approveInputVersion(
                        dependency.datasetRef(),
                        approvedInputVersions,
                        enforcedFailures);
            }
        }
        
        // Determine final result
        if (!enforcedFailures.isEmpty()) {
            String blockReason = buildBlockReason(enforcedFailures);
            log.info("Job BLOCKED due to {} unmet ENFORCED dependencies: job={} reason={}", 
                enforcedFailures.size(), context.getJobName(), blockReason);
            return GuardResult.blocked(enforcedFailures, blockReason);
        }
        
        if (!documentationOnlyFailures.isEmpty()) {
            log.info("Job can proceed with {} DOCUMENTATION_ONLY warnings: job={}",
                documentationOnlyFailures.size(), context.getJobName());
            return GuardResult.readyWithWarnings(
                    documentationOnlyFailures,
                    approvedInputVersions);
        }

        log.info("All dependencies satisfied for job={}", context.getJobName());
        return GuardResult.ready(approvedInputVersions);
    }
    
    @Override
    public List<DatasetDependency> parseDependencies(JobExecutionContext context) {
        List<Map<String, Object>> rawDeps = context.getDependsOnDatasets();
        
        if (rawDeps.isEmpty()) {
            return List.of();
        }
        
        List<DatasetDependency> dependencies = new ArrayList<>();
        
        for (Map<String, Object> rawDep : rawDeps) {
            try {
                DatasetDependency dep = DatasetDependency.fromConfig(rawDep);
                dependencies.add(dep);
            } catch (Exception e) {
                log.error("Failed to parse dataset dependency for job={}: config={}", 
                    context.getJobName(), rawDep, e);
                // Continue parsing other dependencies
            }
        }
        
        return dependencies;
    }
    
    /**
     * Evaluate all conditions for a single dataset dependency.
     */
    private List<DependencyCheckResult> evaluateDependency(
        DatasetDependency dependency, 
        JobExecutionContext context
    ) {
        List<DependencyCheckResult> results = new ArrayList<>();
        
        // Create evaluation context
        EvaluationContext evalContext = new EvaluationContext(
            manifestReader,
            context.upstreamVersions(),
            context.getJobName(),
            context.executionId()
        );
        
        // Evaluate each condition
        for (DependencyCondition condition : dependency.conditions()) {
            ConditionEvaluator evaluator = evaluators.get(condition);
            
            if (evaluator == null) {
                log.error("No evaluator registered for condition={}, skipping", condition);
                results.add(DependencyCheckResult.error(
                    dependency.datasetRef(),
                    "No evaluator for condition: " + condition
                ));
                continue;
            }
            
            try {
                // Get condition-specific parameter
                Object param = getParameterForCondition(condition, dependency);
                
                DependencyCheckResult result = evaluator.evaluate(
                    dependency.datasetRef(),
                    param,
                    evalContext
                );
                
                results.add(result);
                
                log.debug("Evaluated condition={} for dataset={} partition={}: status={}",
                    condition,
                    dependency.datasetRef().getDataset(),
                    dependency.datasetRef().getPartition(),
                    result.getStatus());
                
            } catch (Exception e) {
                log.error("Error evaluating condition={} for dataset={} partition={}",
                    condition,
                    dependency.datasetRef().getDataset(),
                    dependency.datasetRef().getPartition(),
                    e);
                results.add(DependencyCheckResult.error(
                    dependency.datasetRef(),
                    "Evaluation error: " + e.getMessage()
                ));
            }
        }
        
        return results;
    }
    
    private void approveInputVersion(
            DatasetRef datasetRef,
            Map<DatasetRef, String> approvedInputVersions,
            List<DependencyCheckResult> enforcedFailures) {
        try {
            Optional<DatasetManifest> manifest =
                    manifestReader.readManifest(datasetRef);
            if (manifest.isEmpty()) {
                enforcedFailures.add(
                        DependencyCheckResult.missing(datasetRef));
                return;
            }

            DatasetManifest approvedManifest = manifest.get();
            if (!approvedManifest.isReady()) {
                enforcedFailures.add(DependencyCheckResult.notReady(
                        datasetRef,
                        approvedManifest.status()));
                return;
            }
            if (approvedManifest.dataVersion() == null
                    || approvedManifest.dataVersion().isBlank()) {
                enforcedFailures.add(DependencyCheckResult.error(
                        datasetRef,
                        "READY manifest has no dataVersion"));
                return;
            }

            approvedInputVersions.put(
                    datasetRef,
                    approvedManifest.dataVersion());
        } catch (Exception exception) {
            enforcedFailures.add(DependencyCheckResult.error(
                    datasetRef,
                    "Failed final manifest approval: "
                            + exception.getMessage()));
        }
    }

    /**
     * Extract condition-specific parameter from dependency config.
     */
    private Object getParameterForCondition(DependencyCondition condition, DatasetDependency dependency) {
        return switch (condition) {
            case MIN_ROW_COUNT -> dependency.getParameter("minRowCount");
            case MAX_FRESHNESS_LAG -> dependency.getParameter("maxFreshnessLag");
            case SUPPORTED_SCHEMA_VERSION -> dependency.getParameter("supportedSchemaVersions");
            case CURRENT_INPUTS -> dependency.getParameter("upstreamDatasets");
            default -> null; // EXISTS, READY, PARTITION_MATCH don't need parameters
        };
    }
    
    /**
     * Build human-readable block reason from failed checks.
     */
    private String buildBlockReason(List<DependencyCheckResult> failures) {
        if (failures.isEmpty()) {
            return "Unknown reason";
        }
        
        // Group failures by status
        Map<DependencyStatus, Long> statusCounts = failures.stream()
            .collect(Collectors.groupingBy(
                DependencyCheckResult::getStatus,
                Collectors.counting()
            ));
        
        // Build summary
        List<String> parts = new ArrayList<>();
        
        if (statusCounts.containsKey(DependencyStatus.MISSING)) {
            parts.add(statusCounts.get(DependencyStatus.MISSING) + " missing");
        }
        if (statusCounts.containsKey(DependencyStatus.NOT_READY)) {
            parts.add(statusCounts.get(DependencyStatus.NOT_READY) + " not ready");
        }
        if (statusCounts.containsKey(DependencyStatus.STALE)) {
            parts.add(statusCounts.get(DependencyStatus.STALE) + " stale");
        }
        if (statusCounts.containsKey(DependencyStatus.INPUT_VERSION_MISMATCH)) {
            parts.add(statusCounts.get(DependencyStatus.INPUT_VERSION_MISMATCH) + " version mismatch");
        }
        if (statusCounts.containsKey(DependencyStatus.EMPTY)) {
            parts.add(statusCounts.get(DependencyStatus.EMPTY) + " insufficient rows");
        }
        if (statusCounts.containsKey(DependencyStatus.ERROR)) {
            parts.add(statusCounts.get(DependencyStatus.ERROR) + " errors");
        }
        
        return String.join(", ", parts);
    }
}
