package com.omni.platform.modules.scheduler.dependencies;

import java.time.Instant;
import java.util.Map;


public record DependencyDecision(
        State state,
        String reasonCode,
        String reason,
        Instant retryAt,
        Map<DatasetRef, String> approvedInputVersions) {

    public DependencyDecision {
        approvedInputVersions = Map.copyOf(approvedInputVersions);
    }

    public static DependencyDecision ready(Map<DatasetRef, String> approvedInputVersions) {
        return new DependencyDecision(State.READY, null, null, null, approvedInputVersions);
    }

    public static DependencyDecision waiting(String reasonCode, String reason, Instant retryAt) {
        return new DependencyDecision(State.WAITING, reasonCode, reason, retryAt, Map.of());
    }

    public static DependencyDecision blocked(String reasonCode, String reason) {
        return new DependencyDecision(State.BLOCKED, reasonCode, reason, null, Map.of());
    }

    public enum State {
        READY,
        WAITING,
        BLOCKED
    }
}
