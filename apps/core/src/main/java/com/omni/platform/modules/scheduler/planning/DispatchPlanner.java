package com.omni.platform.modules.scheduler.planning;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.planning.DispatchPlan.Reason;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Selection;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Status;
import com.omni.platform.modules.scheduler.topology.StaticJobTopology;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

/**
 * Pure deterministic selection and quota planning over a bounded backlog snapshot.
 * Selects the first dispatchable node on each root path and deduplicates shared scopes.
 * Snapshot counts are hints, not reservations or dataset-readiness decisions: the
 * dispatcher must still evaluate dependencies and atomically claim eligible work.
 * No clock, database, publication, or cursor persistence is accessed here.
 */
@Component
public final class DispatchPlanner {

    private final StaticJobTopology topology;

    @Autowired
    public DispatchPlanner(StaticJobTopologyProvider topologyProvider) {
        this(Objects.requireNonNull(topologyProvider, "topologyProvider").topology());
    }

    DispatchPlanner(StaticJobTopology topology) {
        this.topology = Objects.requireNonNull(topology, "topology");
    }

    /**
     * Plans using explicit time inputs so identical inputs produce identical results.
     * Incomplete snapshots take precedence over freshness checks. A complete snapshot
     * is usable only when its age is between zero and {@code maxSnapshotAge}, inclusive.
     * Too-old or future-dated snapshots fail closed without allocating quota or
     * advancing the root cursor; callers should obtain a fresh snapshot or use their
     * separately guarded fallback policy rather than interpreting rejection as no work.
     *
     * @param snapshot timestamped backlog counts and completeness evidence
     * @param dispatchBudget positive upper bound on total selection quota
     * @param rootCursor root to consider first, or null for stable default ordering
     * @param evaluatedAt caller's current evaluation time, normally captured after the
     *                    snapshot read; do not substitute the snapshot timestamp, which
     *                    would conceal aging; use the same clock basis as capture time
     * @param maxSnapshotAge positive caller-owned freshness tolerance, not a job timeout,
     *                       claim lease, or readiness guarantee; no default is chosen here
     * @return bounded selections, or a typed rejection/no-work result
     * @throws IllegalArgumentException if budget/age tolerance is non-positive or the
     *                                  cursor is not a known root
     */
    public DispatchPlan plan(
            BacklogSnapshot snapshot,
            int dispatchBudget,
            TopologyNodeKey rootCursor,
            Instant evaluatedAt,
            Duration maxSnapshotAge) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(maxSnapshotAge, "maxSnapshotAge");
        if (maxSnapshotAge.isNegative() || maxSnapshotAge.isZero()) {
            throw new IllegalArgumentException("maxSnapshotAge must be positive");
        }
        if (dispatchBudget <= 0) {
            throw new IllegalArgumentException("dispatchBudget must be positive");
        }
        List<TopologyNodeKey> roots = rotatedRoots(rootCursor);
        TopologyNodeKey unchangedCursor = roots.isEmpty() ? null : roots.getFirst();
        if (!snapshot.complete()) {
            return new DispatchPlan(
                    snapshot.capturedAt(),
                    Status.INCOMPLETE_SNAPSHOT,
                    dispatchBudget,
                    unchangedCursor,
                    List.of());
        }

        // Reject future capture times as well as expired evidence; equality is valid.
        // Time is supplied by the caller instead of reading a clock inside this planner.
        Duration age = Duration.between(snapshot.capturedAt(), evaluatedAt);
        if (age.isNegative() || age.compareTo(maxSnapshotAge) > 0) {
            return new DispatchPlan(
                    snapshot.capturedAt(),
                    Status.STALE_SNAPSHOT,
                    dispatchBudget,
                    unchangedCursor,
                    List.of());
        }

        List<RootCandidates> candidates = candidates(snapshot, roots);
        if (candidates.isEmpty()) {
            return new DispatchPlan(
                    snapshot.capturedAt(),
                    Status.NO_DISPATCHABLE_WORK,
                    dispatchBudget,
                    unchangedCursor,
                    List.of());
        }

        Map<Candidate, Integer> allocations = allocate(candidates, dispatchBudget);
        List<Selection> selections = new ArrayList<>(allocations.size());
        int order = 0;
        for (Map.Entry<Candidate, Integer> allocation : allocations.entrySet()) {
            Candidate candidate = allocation.getKey();
            selections.add(
                    new Selection(
                            candidate.root(),
                            candidate.scope(),
                            allocation.getValue(),
                            candidate.reason(),
                            order++));
        }
        TopologyNodeKey cursor = nextCursorAfterAllocation(roots, allocations);
        return new DispatchPlan(
                snapshot.capturedAt(), Status.PLANNED, dispatchBudget, cursor, selections);
    }

    private List<RootCandidates> candidates(BacklogSnapshot snapshot, List<TopologyNodeKey> roots) {
        List<RootCandidates> result = new ArrayList<>();
        // Deduplication only: these scopes are not database claims or reservations.
        Set<DispatchScope> claimedScopes = new HashSet<>();
        Map<TopologyNodeKey, Integer> nodeRootCounts = rootReachability(roots);
        for (TopologyNodeKey root : roots) {
            List<Candidate> scopes = new ArrayList<>();
            for (TopologyNodeKey selectedNode : firstDispatchableNodes(snapshot, root)) {
                snapshot.entriesForNode(selectedNode).stream()
                        .filter(entry -> entry.getValue().hasDispatchableWork())
                        .filter(entry -> claimedScopes.add(entry.getKey()))
                        .map(
                                entry ->
                                        new Candidate(
                                                root,
                                                entry.getKey(),
                                                entry.getValue().dispatchable(),
                                                nodeRootCounts.getOrDefault(selectedNode, 0) > 1
                                                        ? Reason.SHARED_DESCENDANT
                                                        : Reason.FIRST_DISPATCHABLE_ON_ROOT_PATH))
                        .forEach(scopes::add);
            }
            if (!scopes.isEmpty()) {
                result.add(new RootCandidates(root, scopes));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Propagates only paths that have not yet reached dispatchable work. Topological
     * order and a set avoid enumerating exponentially many paths through joins.
     * A selected node stops its path, but a join can still be reached through another
     * unoccupied path. Delayed/in-flight occupancy is not a whole-node readiness barrier.
     */
    private List<TopologyNodeKey> firstDispatchableNodes(
            BacklogSnapshot snapshot, TopologyNodeKey root) {
        Set<TopologyNodeKey> frontier = new LinkedHashSet<>();
        frontier.add(root);
        List<TopologyNodeKey> selected = new ArrayList<>();
        for (TopologyNodeKey node : topology.topologicalOrder()) {
            if (!frontier.contains(node)) {
                continue;
            }
            if (snapshot.entriesForNode(node).stream()
                    .anyMatch(entry -> entry.getValue().hasDispatchableWork())) {
                selected.add(node);
            } else {
                frontier.addAll(topology.directDownstream(node));
            }
        }
        return List.copyOf(selected);
    }

    private Map<TopologyNodeKey, Integer> rootReachability(List<TopologyNodeKey> roots) {
        Map<TopologyNodeKey, Integer> counts = new LinkedHashMap<>();
        for (TopologyNodeKey root : roots) {
            counts.merge(root, 1, Integer::sum);
            topology.descendants(root, StaticJobTopology.DEFAULT_MAX_TRAVERSAL_DEPTH)
                    .forEach(node -> counts.merge(node, 1, Integer::sum));
        }
        return counts;
    }

    /**
     * Gives each root at most one unit per round, consuming its scopes in stable order.
     * Exhausted roots leave remaining budget available to others; allocation never
     * exceeds either the dispatch budget or a scope's snapshot dispatchable count.
     */
    private Map<Candidate, Integer> allocate(List<RootCandidates> roots, int budget) {
        Map<Candidate, Integer> allocations = new LinkedHashMap<>();
        Map<Candidate, Long> remaining = new LinkedHashMap<>();
        roots.forEach(
                root ->
                        root.candidates()
                                .forEach(
                                        candidate ->
                                                remaining.put(candidate, candidate.available())));
        int allocated = 0;
        boolean progressed = true;
        while (allocated < budget && progressed) {
            progressed = false;
            for (RootCandidates root : roots) {
                for (Candidate candidate : root.candidates()) {
                    long available = remaining.get(candidate);
                    if (available <= 0 || allocated >= budget) {
                        continue;
                    }
                    allocations.merge(candidate, 1, Integer::sum);
                    remaining.put(candidate, available - 1);
                    allocated++;
                    progressed = true;
                    break;
                }
            }
        }
        return allocations;
    }

    private List<TopologyNodeKey> rotatedRoots(TopologyNodeKey cursor) {
        List<TopologyNodeKey> roots = new ArrayList<>(topology.roots());
        if (roots.isEmpty() || cursor == null) {
            return List.copyOf(roots);
        }
        int index = roots.indexOf(cursor);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown root cursor: " + cursor);
        }
        List<TopologyNodeKey> rotated = new ArrayList<>(roots.size());
        rotated.addAll(roots.subList(index, roots.size()));
        rotated.addAll(roots.subList(0, index));
        return List.copyOf(rotated);
    }

    private TopologyNodeKey nextCursorAfterAllocation(
            List<TopologyNodeKey> roots, Map<Candidate, Integer> allocations) {
        if (roots.isEmpty()) {
            return null;
        }
        TopologyNodeKey last =
                allocations.keySet().stream()
                        .reduce((ignored, candidate) -> candidate)
                        .orElseThrow()
                        .root();
        int index = roots.indexOf(last);
        return roots.get((index + 1) % roots.size());
    }

    private record Candidate(
            TopologyNodeKey root, DispatchScope scope, long available, Reason reason) {}

    private record RootCandidates(TopologyNodeKey root, List<Candidate> candidates) {}
}
