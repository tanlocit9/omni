package com.omni.platform.modules.scheduler.planning;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Bounded immutable scheduler backlog observation at one point in time. */
public record BacklogSnapshot(
        Instant capturedAt,
        boolean complete,
        int groupLimit,
        Map<DispatchScope, BacklogCounts> entries) {

    public BacklogSnapshot {
        Objects.requireNonNull(capturedAt, "capturedAt");
        if (groupLimit <= 0) {
            throw new IllegalArgumentException("groupLimit must be positive");
        }
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > groupLimit) {
            throw new IllegalArgumentException("Snapshot entries exceed groupLimit");
        }
        TreeMap<DispatchScope, BacklogCounts> sorted = new TreeMap<>();
        entries.forEach(
                (scope, counts) -> {
                    Objects.requireNonNull(scope, "scope");
                    Objects.requireNonNull(counts, "counts");
                    if (sorted.putIfAbsent(scope, counts) != null) {
                        throw new IllegalArgumentException("Duplicate dispatch scope: " + scope);
                    }
                });
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public List<Map.Entry<DispatchScope, BacklogCounts>> entriesForNode(
            com.omni.platform.modules.scheduler.topology.TopologyNodeKey node) {
        Objects.requireNonNull(node, "node");
        return entries.entrySet().stream()
                .filter(entry -> entry.getKey().node().equals(node))
                .toList();
    }
}
