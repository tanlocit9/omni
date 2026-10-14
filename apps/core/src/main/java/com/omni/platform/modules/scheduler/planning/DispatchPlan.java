package com.omni.platform.modules.scheduler.planning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

/** Pure planner output; it carries no SQL, readiness decision, or claim state. */
public record DispatchPlan(
        Instant snapshotAt,
        Status status,
        int dispatchBudget,
        TopologyNodeKey nextRootCursor,
        List<Selection> selections) {

    public DispatchPlan {
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        Objects.requireNonNull(status, "status");
        if (dispatchBudget <= 0) {
            throw new IllegalArgumentException("dispatchBudget must be positive");
        }
        selections = List.copyOf(Objects.requireNonNull(selections, "selections"));
        int allocated = selections.stream().mapToInt(Selection::quota).sum();
        if (allocated > dispatchBudget) {
            throw new IllegalArgumentException("Selection quota exceeds dispatchBudget");
        }
        if (status != Status.PLANNED && !selections.isEmpty()) {
            throw new IllegalArgumentException("Non-planned result must not contain selections");
        }
    }

    public enum Status {
        PLANNED,
        INCOMPLETE_SNAPSHOT,
        STALE_SNAPSHOT,
        NO_DISPATCHABLE_WORK
    }

    public record Selection(
            TopologyNodeKey root,
            DispatchScope scope,
            int quota,
            Reason reason,
            int order) {

        public Selection {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(reason, "reason");
            if (quota <= 0) {
                throw new IllegalArgumentException("quota must be positive");
            }
            if (order < 0) {
                throw new IllegalArgumentException("order must not be negative");
            }
        }
    }

    public enum Reason {
        FIRST_DISPATCHABLE_ON_ROOT_PATH,
        SHARED_DESCENDANT,
        UNUSED_QUOTA_REALLOCATION
    }
}
