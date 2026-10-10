package com.omni.platform.modules.scheduler.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.DefinitionMapping;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.Edge;

/** Validated immutable Platform-local job topology. */
public final class StaticJobTopology {

    public static final int DEFAULT_MAX_NODES = 256;
    public static final int DEFAULT_MAX_EDGES = 1024;
    public static final int DEFAULT_MAX_TRAVERSAL_DEPTH = 64;

    private final Set<TopologyNodeKey> nodes;
    private final Map<TopologyNodeKey, Set<TopologyNodeKey>> downstream;
    private final Map<TopologyNodeKey, Set<TopologyNodeKey>> upstream;
    private final Map<JobDefinitionIdentity, TopologyNodeKey> definitionMappings;
    private final List<TopologyNodeKey> topologicalOrder;

    private StaticJobTopology(
            Set<TopologyNodeKey> nodes,
            Map<TopologyNodeKey, Set<TopologyNodeKey>> downstream,
            Map<TopologyNodeKey, Set<TopologyNodeKey>> upstream,
            Map<JobDefinitionIdentity, TopologyNodeKey> definitionMappings,
            List<TopologyNodeKey> topologicalOrder) {
        this.nodes = Collections.unmodifiableSet(new LinkedHashSet<>(nodes));
        this.downstream = immutableAdjacency(downstream);
        this.upstream = immutableAdjacency(upstream);
        this.definitionMappings = Collections.unmodifiableMap(new LinkedHashMap<>(definitionMappings));
        this.topologicalOrder = List.copyOf(topologicalOrder);
    }

    public static StaticJobTopology create(StaticTopologyDeclaration declaration) {
        return create(declaration, DEFAULT_MAX_NODES, DEFAULT_MAX_EDGES);
    }

    public static StaticJobTopology create(
            StaticTopologyDeclaration declaration,
            int maxNodes,
            int maxEdges) {
        Objects.requireNonNull(declaration, "declaration");
        if (maxNodes <= 0 || maxEdges < 0) {
            throw new IllegalArgumentException("Topology bounds must be positive");
        }
        validateDuplicatesAndBounds(declaration, maxNodes, maxEdges);

        Set<TopologyNodeKey> nodes = new TreeSet<>(declaration.nodes());
        Map<TopologyNodeKey, Set<TopologyNodeKey>> downstream = adjacency(nodes);
        Map<TopologyNodeKey, Set<TopologyNodeKey>> upstream = adjacency(nodes);
        for (Edge edge : declaration.edges()) {
            requireKnown(nodes, edge.upstream(), "edge upstream");
            requireKnown(nodes, edge.downstream(), "edge downstream");
            if (edge.upstream().equals(edge.downstream())) {
                throw new IllegalArgumentException("Self edge is not allowed: " + edge.upstream());
            }
            downstream.get(edge.upstream()).add(edge.downstream());
            upstream.get(edge.downstream()).add(edge.upstream());
        }

        Map<JobDefinitionIdentity, TopologyNodeKey> mappings = new TreeMap<>();
        for (DefinitionMapping mapping : declaration.definitionMappings()) {
            requireKnown(nodes, mapping.node(), "definition mapping");
            TopologyNodeKey previous = mappings.putIfAbsent(mapping.definition(), mapping.node());
            if (previous != null) {
                throw new IllegalArgumentException("Ambiguous definition mapping: " + mapping.definition());
            }
        }
        Set<TopologyNodeKey> mappedNodes = new TreeSet<>(mappings.values());
        Set<TopologyNodeKey> unmappedNodes = new TreeSet<>(nodes);
        unmappedNodes.removeAll(mappedNodes);
        if (!unmappedNodes.isEmpty()) {
            throw new IllegalArgumentException("Topology nodes without definition mappings: " + unmappedNodes);
        }

        List<TopologyNodeKey> order = topologicalOrder(nodes, downstream, upstream);
        return new StaticJobTopology(nodes, downstream, upstream, mappings, order);
    }

    public Set<TopologyNodeKey> nodes() {
        return nodes;
    }

    public Set<TopologyNodeKey> roots() {
        Set<TopologyNodeKey> roots = new LinkedHashSet<>();
        for (TopologyNodeKey node : topologicalOrder) {
            if (upstream.get(node).isEmpty()) {
                roots.add(node);
            }
        }
        return Collections.unmodifiableSet(roots);
    }

    public Set<TopologyNodeKey> directUpstream(TopologyNodeKey node) {
        return neighbors(upstream, node);
    }

    public Set<TopologyNodeKey> directDownstream(TopologyNodeKey node) {
        return neighbors(downstream, node);
    }

    public Set<TopologyNodeKey> ancestors(TopologyNodeKey node, int maxDepth) {
        return traverse(node, upstream, maxDepth);
    }

    public Set<TopologyNodeKey> descendants(TopologyNodeKey node, int maxDepth) {
        return traverse(node, downstream, maxDepth);
    }

    public TopologyNodeKey nodeFor(JobDefinitionIdentity definition) {
        TopologyNodeKey node = definitionMappings.get(Objects.requireNonNull(definition, "definition"));
        if (node == null) {
            throw new IllegalArgumentException("Unknown job definition mapping: " + definition);
        }
        return node;
    }

    public List<TopologyNodeKey> topologicalOrder() {
        return topologicalOrder;
    }

    public String toMermaid() {
        StringBuilder output = new StringBuilder("flowchart TD\n");
        for (TopologyNodeKey node : topologicalOrder) {
            output.append("    ").append(mermaidId(node)).append("[\"")
                    .append(node.value()).append("\"]\n");
        }
        for (TopologyNodeKey source : topologicalOrder) {
            for (TopologyNodeKey target : downstream.get(source)) {
                output.append("    ").append(mermaidId(source)).append(" --> ")
                        .append(mermaidId(target)).append('\n');
            }
        }
        return output.toString();
    }

    private Set<TopologyNodeKey> traverse(
            TopologyNodeKey start,
            Map<TopologyNodeKey, Set<TopologyNodeKey>> adjacency,
            int maxDepth) {
        requireKnown(nodes, start, "traversal node");
        if (maxDepth < 0 || maxDepth > DEFAULT_MAX_TRAVERSAL_DEPTH) {
            throw new IllegalArgumentException("Traversal depth must be between 0 and "
                    + DEFAULT_MAX_TRAVERSAL_DEPTH);
        }
        Set<TopologyNodeKey> visited = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(start, 0));
        while (!queue.isEmpty()) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() == maxDepth) {
                continue;
            }
            for (TopologyNodeKey neighbor : adjacency.get(current.node())) {
                if (visited.add(neighbor)) {
                    queue.addLast(new NodeDepth(neighbor, current.depth() + 1));
                }
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    private static void validateDuplicatesAndBounds(
            StaticTopologyDeclaration declaration,
            int maxNodes,
            int maxEdges) {
        if (declaration.nodes().size() > maxNodes) {
            throw new IllegalArgumentException("Topology node bound exceeded: " + maxNodes);
        }
        if (declaration.edges().size() > maxEdges) {
            throw new IllegalArgumentException("Topology edge bound exceeded: " + maxEdges);
        }
        rejectDuplicates(declaration.nodes(), "Duplicate topology node: ");
        rejectDuplicates(declaration.edges(), "Duplicate topology edge: ");
    }

    private static <T> void rejectDuplicates(Collection<T> values, String message) {
        Set<T> unique = new LinkedHashSet<>();
        for (T value : values) {
            if (!unique.add(value)) {
                throw new IllegalArgumentException(message + value);
            }
        }
    }

    private static Map<TopologyNodeKey, Set<TopologyNodeKey>> adjacency(Set<TopologyNodeKey> nodes) {
        Map<TopologyNodeKey, Set<TopologyNodeKey>> result = new TreeMap<>();
        nodes.forEach(node -> result.put(node, new TreeSet<>()));
        return result;
    }

    private static Map<TopologyNodeKey, Set<TopologyNodeKey>> immutableAdjacency(
            Map<TopologyNodeKey, Set<TopologyNodeKey>> source) {
        Map<TopologyNodeKey, Set<TopologyNodeKey>> result = new LinkedHashMap<>();
        source.forEach((node, neighbors) -> result.put(
                node, Collections.unmodifiableSet(new LinkedHashSet<>(neighbors))));
        return Collections.unmodifiableMap(result);
    }

    private static List<TopologyNodeKey> topologicalOrder(
            Set<TopologyNodeKey> nodes,
            Map<TopologyNodeKey, Set<TopologyNodeKey>> downstream,
            Map<TopologyNodeKey, Set<TopologyNodeKey>> upstream) {
        Map<TopologyNodeKey, Integer> indegrees = new TreeMap<>();
        nodes.forEach(node -> indegrees.put(node, upstream.get(node).size()));
        PriorityQueue<TopologyNodeKey> ready = new PriorityQueue<>();
        indegrees.forEach((node, degree) -> {
            if (degree == 0) {
                ready.add(node);
            }
        });
        List<TopologyNodeKey> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            TopologyNodeKey node = ready.remove();
            order.add(node);
            for (TopologyNodeKey child : downstream.get(node)) {
                int remaining = indegrees.compute(child, (ignored, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.add(child);
                }
            }
        }
        if (order.size() != nodes.size()) {
            Set<TopologyNodeKey> cyclic = new TreeSet<>(nodes);
            cyclic.removeAll(order);
            throw new IllegalArgumentException("Topology contains a cycle involving: " + cyclic);
        }
        return List.copyOf(order);
    }

    private static Set<TopologyNodeKey> neighbors(
            Map<TopologyNodeKey, Set<TopologyNodeKey>> adjacency,
            TopologyNodeKey node) {
        requireKnown(adjacency.keySet(), node, "topology node");
        return adjacency.get(node);
    }

    private static void requireKnown(Set<TopologyNodeKey> nodes, TopologyNodeKey node, String role) {
        Objects.requireNonNull(node, role);
        if (!nodes.contains(node)) {
            throw new IllegalArgumentException("Unknown " + role + ": " + node);
        }
    }

    private static String mermaidId(TopologyNodeKey node) {
        return "n_" + node.value().replace('-', '_');
    }

    private record NodeDepth(TopologyNodeKey node, int depth) {
    }
}
