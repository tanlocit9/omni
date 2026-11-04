package com.omni.platform.modules.scheduler.dependencies;

import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * V1 Platform-local registry. It adapts the existing manifest guard without
 * exporting policy implementations or moving dependency logic into workers.
 */
@Service
public class PlatformDependencyRegistry implements DependencyRegistry {

    private static final Duration WAIT_RETRY = Duration.ofSeconds(30);

    private final JobDependencyGuard guard;
    private final JobDependencyContextFactory contextFactory;
    @org.springframework.beans.factory.annotation.Autowired private ManifestReader metadataReader;

    @org.springframework.beans.factory.annotation.Autowired
    private com.omni.platform.modules.scheduler.metadata.OutputWriteAdmission outputWriteAdmission;

    @org.springframework.beans.factory.annotation.Autowired
    private com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness workReadiness;

    public PlatformDependencyRegistry(
            JobDependencyGuard guard, JobDependencyContextFactory contextFactory) {
        this.guard = guard;
        this.contextFactory = contextFactory;
    }

    @Override
    public DependencyDecision evaluate(DependencyRequest request) {
        if (outputWriteAdmission != null && outputWriteAdmission.waiting(request.executionId()))
            return DependencyDecision.waiting(
                    "OUTPUT_WRITE_WAITING",
                    "Earlier output generation is unfinished or has unresolved timeout ownership",
                    request.evaluatedAt().plus(WAIT_RETRY));
        var context =
                contextFactory.create(
                        request.jobDefinition(),
                        request.executionId().toString(),
                        request.workType(),
                        request.workKey());
        Object workDate = request.executionMetadata().get("workBusinessDate");
        if (workDate != null
                && request.jobDefinition().getJobType().name().equals("SYNC_SIGNALS")) {
            context = signalContext(request, context);
        }
        if (workReadiness != null && workReadiness.enrolled(request.executionId())) {
            var declared = guard.parseDependencies(context);
            if (declared.stream()
                    .filter(DatasetDependency::isEnforced)
                    .anyMatch(
                            d ->
                                    d.conditions().stream()
                                            .anyMatch(
                                                    c ->
                                                            !java.util.Set.of(
                                                                            DependencyCondition
                                                                                    .EXISTS,
                                                                            DependencyCondition
                                                                                    .READY,
                                                                            DependencyCondition
                                                                                    .PARTITION_MATCH)
                                                                    .contains(c))))
                return DependencyDecision.blocked(
                        "WORK_SCOPE_POLICY_UNSUPPORTED",
                        "Dependency policy requires an explicit work-scope adapter");
            Map<DatasetRef, String> refs = new java.util.LinkedHashMap<>();
            for (var dependency : declared)
                if (dependency.isEnforced()) refs.put(dependency.datasetRef(), "work-scope");
            var bindings = workReadiness.bind(request.executionId(), refs);
            if (bindings == null)
                return DependencyDecision.waiting(
                        "UPSTREAM_IDENTITY_NOT_READY",
                        "Frozen upstream work rows are not READY",
                        request.evaluatedAt().plus(WAIT_RETRY));
            return DependencyDecision.ready(bindings);
        }
        var result = guard.checkDependencies(context);
        if (result.canExecute()) {
            if (workDate != null
                    && java.util.List.of("SYNC_INDICATORS", "SYNC_SIGNALS")
                            .contains(request.jobDefinition().getJobType().name())) {
                var day = java.time.LocalDate.parse(workDate.toString());
                for (var binding : result.approvedInputVersions().entrySet()) {
                    if (metadataReader == null
                            || !metadataReader.coversDate(
                                    binding.getKey(), binding.getValue(), day))
                        return DependencyDecision.waiting(
                                "INPUT_DATE_NOT_COVERED",
                                "Exact coverage missing for "
                                        + binding.getKey()
                                        + " date="
                                        + day
                                        + " version="
                                        + binding.getValue(),
                                request.evaluatedAt().plus(WAIT_RETRY));
                }
            }
            var bindings =
                    workReadiness == null
                            ? result.approvedInputVersions()
                            : workReadiness.bind(
                                    request.executionId(), result.approvedInputVersions());
            if (bindings == null)
                return DependencyDecision.waiting(
                        "UPSTREAM_IDENTITY_NOT_READY",
                        "Frozen upstream work/attempt/version is not READY",
                        request.evaluatedAt().plus(WAIT_RETRY));
            return DependencyDecision.ready(bindings);
        }
        boolean terminal =
                result.checks().stream()
                        .anyMatch(check -> check.getStatus() == DependencyStatus.INVALID_SCHEMA);
        String reason =
                result.blockReason() == null ? "dependency not ready" : result.blockReason();
        if (terminal) {
            return DependencyDecision.blocked("DEPENDENCY_INCOMPATIBLE", reason);
        }
        return DependencyDecision.waiting(
                "DEPENDENCY_WAITING", reason, request.evaluatedAt().plus(WAIT_RETRY));
    }

    private static JobExecutionContext signalContext(
            DependencyRequest request, JobExecutionContext previous) {
        var symbol = request.workKey().toLowerCase(java.util.Locale.ROOT).split("-", 2);
        if (symbol.length != 2) throw new IllegalArgumentException("Invalid signal symbol");
        Object expansion = request.executionMetadata().get("workExpansion");
        if (!(expansion instanceof Map<?, ?> snapshot)
                || !(snapshot.get("parameters") instanceof Map<?, ?> parameters))
            throw new IllegalArgumentException("Signal requires accepted parameters");
        String timeframe =
                java.util.Objects.requireNonNull(parameters.get("timeframe"))
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT);
        String strategy = java.util.Objects.requireNonNull(parameters.get("strategy")).toString();
        java.util.List<Map<String, Object>> dependencies = new java.util.ArrayList<>();
        dependencies.add(dependency("eod", Map.of("exchange", symbol[0], "code", symbol[1])));
        if (strategy.equalsIgnoreCase("CONFIRMED_TREND_EQUALS")) {
            for (String component : java.util.List.of("trend_momentum_v1", "ichimoku_v1"))
                dependencies.add(
                        dependency(
                                "signals",
                                Map.of(
                                        "exchange",
                                        symbol[0],
                                        "timeframe",
                                        timeframe,
                                        "strategy",
                                        component)));
        } else {
            dependencies.add(
                    dependency(
                            "indicators",
                            Map.of(
                                    "exchange",
                                    symbol[0],
                                    "code",
                                    symbol[1],
                                    "source",
                                    "ad_close",
                                    "timeframe",
                                    timeframe)));
        }
        return new JobExecutionContext(
                previous.jobDefinition(),
                previous.executionId(),
                previous.upstreamVersions(),
                dependencies);
    }

    private static Map<String, Object> dependency(String dataset, Map<String, String> partition) {
        return Map.of(
                "dataset",
                dataset,
                "partition",
                partition,
                "conditions",
                java.util.List.of("EXISTS", "READY"),
                "mode",
                "ENFORCED");
    }
}
