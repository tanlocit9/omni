package com.omni.platform.modules.scheduler.planning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Progress;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Run;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;

/** Pure I4 planning checkpoint. No Spring/runtime caller until guarded admission is integrated. */
public final class JobRunDispatchPlanner {
    public Plan plan(
            JobRunDispatchSnapshot snapshot,
            int budget,
            UUID cursor,
            Instant evaluatedAt,
            Duration maxAge) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(maxAge, "maxAge");
        if (budget < 1 || budget > 100 || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("Bounded positive budget and snapshot age required");
        }
        if (!snapshot.complete()) {
            return new Plan(Status.INCOMPLETE, cursor, List.of());
        }
        Duration age = Duration.between(snapshot.capturedAt(), evaluatedAt);
        if (age.isNegative() || age.compareTo(maxAge) > 0) {
            return new Plan(Status.STALE, cursor, List.of());
        }
        List<Run> runs = snapshot.runs().stream().filter(Run::complete).toList();
        if (runs.isEmpty() && !snapshot.runs().isEmpty()) {
            return new Plan(Status.INCOMPLETE, cursor, List.of());
        }
        int start = 0;
        for (int index = 0; index < runs.size(); index++) {
            if (runs.get(index).id().equals(cursor)) start = index;
        }
        List<Candidate> candidates = new ArrayList<>();
        // One round-robin turn per run; eligible sibling roots share that run's turn.
        List<List<Candidate>> byRun = new ArrayList<>();
        for (int offset = 0; offset < runs.size(); offset++) {
            Run run = runs.get((start + offset) % runs.size());
            List<Candidate> eligible =
                    run.steps().stream()
                            .filter(
                                    step ->
                                            step.progress() == Progress.UNFINISHED
                                                    && step.outbox().eligible() > 0)
                            .filter(
                                    step ->
                                            step.predecessors().stream()
                                                    .allMatch(
                                                            predecessor ->
                                                                    run.steps()
                                                                                    .get(
                                                                                            predecessor)
                                                                                    .progress()
                                                                            == Progress.SUCCESS))
                            .map(step -> new Candidate(run.id(), step))
                            .toList();
            if (!eligible.isEmpty()) byRun.add(eligible);
        }
        int remaining = budget;
        UUID lastAllocatedRun = null;
        while (remaining > 0) {
            boolean allocated = false;
            for (List<Candidate> eligible : byRun) {
                long allocatedToRun =
                        eligible.stream().mapToLong(candidate -> candidate.quota).sum();
                // Rotate sibling selection too; unused quota is immediately available to other
                // runs.
                for (int offset = 0; offset < eligible.size(); offset++) {
                    Candidate candidate =
                            eligible.get((int) ((allocatedToRun + offset) % eligible.size()));
                    if (candidate.quota >= candidate.step.outbox().eligible()) continue;
                    if (candidate.quota++ == 0) candidates.add(candidate);
                    remaining--;
                    lastAllocatedRun = candidate.runId;
                    allocated = true;
                    break;
                }
                if (remaining == 0) break;
            }
            if (!allocated) break;
        }
        List<Selection> selections =
                candidates.stream()
                        .map(
                                candidate ->
                                        new Selection(
                                                candidate.runId,
                                                candidate.step.index(),
                                                candidate.step.definitionId(),
                                                candidate.step.scope(),
                                                candidate.quota,
                                                candidate.step.predecessors().isEmpty()
                                                        ? Reason.ROOT_STEP
                                                        : Reason.PREDECESSORS_SUCCEEDED))
                        .toList();
        UUID next = cursor;
        if (!selections.isEmpty()) {
            // Cursor is advisory fairness state, not authoritative runtime membership.
            int lastIndex = 0;
            for (int index = 0; index < runs.size(); index++)
                if (runs.get(index).id().equals(lastAllocatedRun)) lastIndex = index;
            next = runs.get((lastIndex + 1) % runs.size()).id();
        }
        return new Plan(selections.isEmpty() ? Status.WAIT : Status.PLANNED, next, selections);
    }

    private static final class Candidate {
        private final UUID runId;
        private final Step step;
        private int quota;

        private Candidate(UUID runId, Step step) {
            this.runId = runId;
            this.step = step;
        }
    }

    public record Selection(
            UUID jobRunId,
            int stepIndex,
            UUID definitionId,
            Scope scope,
            int quota,
            Reason reason) {}

    public record Plan(Status status, UUID nextCursor, List<Selection> selections) {
        public Plan {
            selections = List.copyOf(selections);
        }
    }

    public enum Status {
        PLANNED,
        WAIT,
        INCOMPLETE,
        STALE
    }

    public enum Reason {
        ROOT_STEP,
        PREDECESSORS_SUCCEEDED
    }
}
