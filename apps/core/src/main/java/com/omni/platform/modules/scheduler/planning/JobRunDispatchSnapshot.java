package com.omni.platform.modules.scheduler.planning;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;

/** Read-only hints over frozen run membership; never a reservation or dataset READY verdict. */
public record JobRunDispatchSnapshot(Instant capturedAt, boolean complete, List<Run> runs) {
    public JobRunDispatchSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        runs = List.copyOf(runs);
        if (runs.stream().map(Run::id).distinct().count() != runs.size()) {
            throw new IllegalArgumentException("Duplicate JobRun snapshot");
        }
    }

    public record Run(UUID id, String state, int expectedSteps, List<Step> steps) {
        public Run {
            Objects.requireNonNull(id, "id");
            steps = List.copyOf(steps);
        }

        public boolean complete() {
            if (!("PREPARED".equals(state) || "RUNNING".equals(state))
                    || expectedSteps < 1
                    || expectedSteps > 1024
                    || steps.size() != expectedSteps) return false;
            for (int index = 0; index < steps.size(); index++) {
                Step step = steps.get(index);
                if (step.index() != index
                        || step.progress() == Progress.UNKNOWN
                        || !step.scope()
                                .businessDate()
                                .equals(steps.getFirst().scope().businessDate())
                        || step.predecessors().stream().distinct().count()
                                != step.predecessors().size()) return false;
                for (int predecessor : step.predecessors()) {
                    if (predecessor < 0 || predecessor >= index) return false;
                }
            }
            java.util.BitSet ancestors = new java.util.BitSet(expectedSteps);
            ancestors.set(expectedSteps - 1);
            for (int index = expectedSteps - 1; index >= 0; index--) {
                if (ancestors.get(index)) steps.get(index).predecessors().forEach(ancestors::set);
            }
            return ancestors.cardinality() == expectedSteps;
        }
    }

    public record Step(
            int index,
            UUID definitionId,
            Scope scope,
            List<Integer> predecessors,
            Membership membership,
            Outbox outbox) {
        public Step {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(membership, "membership");
            Objects.requireNonNull(outbox, "outbox");
            predecessors = List.copyOf(predecessors);
        }

        public Progress progress() {
            if (!membership.closed()
                    || !membership.identitiesMatch()
                    || membership.roots() != 1
                    || membership.expectedChildren() < 0
                    || membership.expectedMessages() < 0
                    || membership.executions() != membership.expectedChildren() + 1L
                    || outbox.total() != membership.expectedMessages()
                    || membership.unknown() != 0
                    || outbox.unknown() != 0) return Progress.UNKNOWN;
            if (membership.failed() > 0 || outbox.blocked() > 0) return Progress.FAILED;
            if (membership.successful() == membership.executions()) return Progress.SUCCESS;
            return Progress.UNFINISHED;
        }
    }

    public record Membership(
            boolean closed,
            boolean identitiesMatch,
            int expectedChildren,
            int expectedMessages,
            long roots,
            long executions,
            long successful,
            long failed,
            long unknown) {}

    /** Mutually exclusive outbox states; published is deliberately separate from execution success. */
    public record Outbox(
            long eligible,
            long leased,
            long retryDelayed,
            long published,
            long blocked,
            long unknown) {
        public Outbox {
            if (eligible < 0
                    || leased < 0
                    || retryDelayed < 0
                    || published < 0
                    || blocked < 0
                    || unknown < 0) throw new IllegalArgumentException("Negative outbox count");
        }

        public long total() {
            return eligible + leased + retryDelayed + published + blocked + unknown;
        }
    }

    public enum Progress {
        SUCCESS,
        UNFINISHED,
        FAILED,
        UNKNOWN
    }
}
