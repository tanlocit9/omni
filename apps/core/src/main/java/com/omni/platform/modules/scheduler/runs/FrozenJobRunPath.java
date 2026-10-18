package com.omni.platform.modules.scheduler.runs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.RequiredPath;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

/** A validated immutable instance of an already resolved path, never a topology registry. */
public record FrozenJobRunPath(
        RequiredPath requiredPath, List<List<Integer>> predecessors, String fingerprint) {
    public static final int MAX_EDGES = 8192;

    public FrozenJobRunPath {
        predecessors = predecessors.stream().map(List::copyOf).toList();
        // Public construction cannot supply a fingerprint or membership inconsistent with the path.
        var expected = inspect(requiredPath);
        if (!expected.predecessors.equals(predecessors)
                || !expected.fingerprint.equals(fingerprint)) {
            throw new IllegalArgumentException(
                    "Frozen path does not match its resolved membership");
        }
    }

    public static FrozenJobRunPath freeze(RequiredPath path) {
        var inspected = inspect(path);
        return new FrozenJobRunPath(path, inspected.predecessors, inspected.fingerprint);
    }

    private static Inspected inspect(RequiredPath path) {
        if (!path.steps().getLast().key().equals(path.target())) {
            throw new IllegalArgumentException("Target must be the final resolved step");
        }
        Map<StepKey, Integer> indices = new HashMap<>();
        List<List<Integer>> membership = new ArrayList<>();
        int edges = 0;
        StringBuilder canonical = new StringBuilder().append(path.contractVersion()).append(';');
        for (int index = 0; index < path.steps().size(); index++) {
            Step step = path.steps().get(index);
            if (!step.node().equals(TopologyNodeKey.forJobType(step.key().definition().jobType()))
                    || !step.key()
                            .scope()
                            .businessDate()
                            .equals(path.target().scope().businessDate())
                    || indices.putIfAbsent(step.key(), index) != null) {
                throw new IllegalArgumentException("Invalid node, date or duplicate scoped step");
            }
            List<Integer> upstream = new ArrayList<>();
            for (StepKey predecessor : step.predecessors()) {
                Integer previous = indices.get(predecessor);
                if (previous == null || previous >= index) {
                    throw new IllegalArgumentException(
                            "Predecessor must be an earlier path member");
                }
                upstream.add(previous);
            }
            if (new HashSet<>(upstream).size() != upstream.size()
                    || (edges += upstream.size()) > MAX_EDGES) {
                throw new IllegalArgumentException(
                        "Duplicate predecessors or path edge bound exceeded");
            }
            upstream.sort(Integer::compareTo);
            membership.add(List.copyOf(upstream));
            var definition = step.key().definition();
            var scope = step.key().scope();
            for (String value :
                    List.of(
                            definition.source().name(),
                            definition.jobType().name(),
                            definition.cronExpression(),
                            scope.type().name(),
                            scope.key(),
                            scope.businessDate().toString())) {
                canonical.append(value.length()).append(':').append(value);
            }
            canonical.append(upstream).append(';');
        }
        Set<Integer> ancestors = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(path.steps().size() - 1);
        while (!queue.isEmpty()) {
            int current = queue.remove();
            if (ancestors.add(current)) queue.addAll(membership.get(current));
        }
        if (ancestors.size() != path.steps().size()) {
            throw new IllegalArgumentException(
                    "Path contains work outside the target ancestor closure");
        }
        try {
            String fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    canonical
                                                            .toString()
                                                            .getBytes(StandardCharsets.UTF_8)));
            return new Inspected(List.copyOf(membership), fingerprint);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    private record Inspected(List<List<Integer>> predecessors, String fingerprint) {}
}
