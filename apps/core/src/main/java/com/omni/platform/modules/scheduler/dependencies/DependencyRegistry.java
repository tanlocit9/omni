package com.omni.platform.modules.scheduler.dependencies;

/**
 * Platform-local decision boundary used by scheduler outbox dispatch.
 *
 * <p>Dependency policies are deliberately in-process: workers and dataset writers
 * receive only work that this boundary has declared ready.
 */
public interface DependencyRegistry {

    DependencyDecision evaluate(DependencyRequest request);
}
