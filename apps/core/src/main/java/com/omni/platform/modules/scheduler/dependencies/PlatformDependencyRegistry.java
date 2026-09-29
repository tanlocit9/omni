package com.omni.platform.modules.scheduler.dependencies;

import java.time.Duration;

import org.springframework.stereotype.Service;


import lombok.RequiredArgsConstructor;

/**
 * V1 Platform-local registry. It adapts the existing manifest guard without
 * exporting policy implementations or moving dependency logic into workers.
 */
@Service
@RequiredArgsConstructor
public class PlatformDependencyRegistry implements DependencyRegistry {

    private static final Duration WAIT_RETRY = Duration.ofSeconds(30);

    private final JobDependencyGuard guard;
    private final JobDependencyContextFactory contextFactory;

    @Override
    public DependencyDecision evaluate(DependencyRequest request) {
        var context = contextFactory.create(
                request.jobDefinition(),
                request.executionId().toString(),
                request.workType(),
                request.workKey());
        var result = guard.checkDependencies(context);
        if (result.canExecute()) {
            return DependencyDecision.ready(result.approvedInputVersions());
        }
        boolean terminal = result.checks().stream().anyMatch(check ->
                check.getStatus() == DependencyStatus.INVALID_SCHEMA);
        String reason = result.blockReason() == null ? "dependency not ready" : result.blockReason();
        if (terminal) {
            return DependencyDecision.blocked("DEPENDENCY_INCOMPATIBLE", reason);
        }
        return DependencyDecision.waiting(
                "DEPENDENCY_WAITING", reason, request.evaluatedAt().plus(WAIT_RETRY));
    }
}
