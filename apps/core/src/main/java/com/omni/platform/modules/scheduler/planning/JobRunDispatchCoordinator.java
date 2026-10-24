package com.omni.platform.modules.scheduler.planning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchStore.PlannedCandidate;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;

import lombok.extern.slf4j.Slf4j;

/** Advisory process-local cursors only; PostgreSQL membership/status/fences remain authoritative. */
@Service
@Slf4j
public class JobRunDispatchCoordinator {
    private final JobRunDispatchStore store;
    private final JobRunDispatchSnapshotReader snapshots;
    private final SchedulerOutboxService outbox;
    private final boolean enabled;
    private final JobRunDispatchPlanner planner = new JobRunDispatchPlanner();
    private UUID runCursor;
    private JobRunDispatchStore.FallbackCursor fallbackCursor;
    private boolean legacyFirst;

    public JobRunDispatchCoordinator(
            JobRunDispatchStore store,
            JobRunDispatchSnapshotReader snapshots,
            SchedulerOutboxService outbox,
            @Value("${app.scheduler.job-run.planning-enabled:true}") boolean enabled,
            @Value("${app.scheduler.job-run.preparation-enabled:false}")
                    boolean preparationEnabled) {
        if (preparationEnabled && !enabled)
            throw new IllegalArgumentException("JobRun preparation requires guarded planning");
        this.store = store;
        this.snapshots = snapshots;
        this.outbox = Objects.requireNonNull(outbox);
        this.enabled = enabled;
        if (enabled) {
            Objects.requireNonNull(store);
            Objects.requireNonNull(snapshots);
        } else
            log.warn(
                    "JOB_RUN_PLANNING_DISABLED explicit historical FIFO rollback; preparation must remain disabled");
    }

    public synchronized Batch load(Instant now, int budget) {
        if (budget < 1 || budget > 100)
            throw new IllegalArgumentException("Dispatch budget must be 1..100");
        if (!enabled)
            return new Batch(
                    Mode.DISABLED,
                    outbox.findCandidates(now, budget * 4).stream()
                            .map(c -> new PlannedCandidate(c, null))
                            .toList());
        long started = System.nanoTime();
        try {
            var legacy = store.findLegacyCandidates(now, budget * 4);
            var runs = store.findRunPage(now, runCursor, 32);
            List<Group> groups = new ArrayList<>();
            int managedBudget = legacy.isEmpty() ? budget : Math.max(1, budget / 2);
            if (!runs.isEmpty()) {
                var snapshot = snapshots.capture(now, runs);
                var plan =
                        planner.plan(
                                snapshot,
                                managedBudget,
                                null,
                                now.plusNanos(System.nanoTime() - started),
                                Duration.ofSeconds(5));
                if (plan.status() == JobRunDispatchPlanner.Status.INCOMPLETE
                        || plan.status() == JobRunDispatchPlanner.Status.STALE)
                    return fallback(now, budget);
                for (var selection : plan.selections()) {
                    var scoped = store.findScopedCandidates(selection, now, selection.quota() * 4);
                    if (scoped.isEmpty()) runCursor = selection.jobRunId();
                    else
                        groups.add(
                                new Group(
                                        scoped.stream()
                                                .map(c -> new PlannedCandidate(c, selection))
                                                .toList(),
                                        selection.quota()));
                }
                if (plan.selections().isEmpty()) runCursor = runs.getLast();
            }
            if (!legacy.isEmpty()) {
                int quota = groups.isEmpty() ? budget : Math.max(1, budget - managedBudget);
                var group =
                        new Group(
                                legacy.stream().map(c -> new PlannedCandidate(c, null)).toList(),
                                quota);
                if (legacyFirst) groups.addFirst(group);
                else groups.add(group);
                legacyFirst = !legacyFirst;
            }
            List<PlannedCandidate> result = new ArrayList<>();
            weave(groups, result, true);
            weave(groups, result, false);
            return new Batch(Mode.PLANNED, result);
        } catch (RuntimeException error) {
            log.warn("JOB_RUN_PLANNING_FALLBACK type={}", error.getClass().getSimpleName());
            return fallback(now, budget);
        }
    }

    private Batch fallback(Instant now, int budget) {
        try {
            var result = store.findFallbackCandidates(now, fallbackCursor, budget * 4);
            fallbackCursor = result.nextCursor();
            log.warn("JOB_RUN_GUARDED_FIFO_FALLBACK candidates={}", result.candidates().size());
            return new Batch(Mode.FALLBACK, result.candidates());
        } catch (RuntimeException error) {
            log.warn("JOB_RUN_DISPATCH_FAIL_CLOSED type={}", error.getClass().getSimpleName());
            return new Batch(Mode.FAILED, List.of());
        }
    }

    /** Primary quotas first, then bounded round-robin overflow to reuse slots lost to WAIT/BLOCKED/races. */
    private static void weave(List<Group> groups, List<PlannedCandidate> result, boolean primary) {
        for (int round = 0; round < 400; round++) {
            boolean added = false;
            for (var group : groups) {
                int index = primary ? round : group.quota() + round;
                if (primary && round >= group.quota() || index >= group.candidates().size())
                    continue;
                result.add(group.candidates().get(index));
                added = true;
            }
            if (!added) break;
        }
    }

    public SchedulerOutboxClaim claim(
            PlannedCandidate candidate,
            Instant now,
            String owner,
            Duration lease,
            Map<DatasetRef, String> approved) {
        if (!enabled)
            return outbox.claimEligible(
                    candidate.candidate().messageId(), now, owner, lease, approved);
        if (candidate.selection() == null)
            return store.claimLegacy(
                    candidate.candidate().messageId(), now, owner, lease, approved);
        skipped(candidate);
        return store.claimPlanned(
                candidate.selection(),
                candidate.candidate().messageId(),
                now,
                owner,
                lease,
                approved);
    }

    /** Advance only after a managed opportunity was actually visited, not hidden by a legacy turn. */
    public synchronized void skipped(PlannedCandidate candidate) {
        if (candidate.selection() != null) runCursor = candidate.selection().jobRunId();
    }

    private record Group(List<PlannedCandidate> candidates, int quota) {}

    public record Batch(Mode mode, List<PlannedCandidate> candidates) {
        public Batch {
            candidates = List.copyOf(candidates);
        }
    }

    public enum Mode {
        PLANNED,
        FALLBACK,
        FAILED,
        DISABLED
    }
}
