package com.omni.platform.modules.scheduler.topology;

import java.util.List;
import java.util.Objects;

/** Raw immutable declaration validated before a static topology is exposed. */
public record StaticTopologyDeclaration(
        List<TopologyNodeKey> nodes,
        List<Edge> edges,
        List<DefinitionMapping> definitionMappings) {

    public StaticTopologyDeclaration {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        definitionMappings = List.copyOf(Objects.requireNonNull(definitionMappings, "definitionMappings"));
    }

    public record Edge(TopologyNodeKey upstream, TopologyNodeKey downstream) {
        public Edge {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(downstream, "downstream");
        }
    }

    public record DefinitionMapping(JobDefinitionIdentity definition, TopologyNodeKey node) {
        public DefinitionMapping {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(node, "node");
        }
    }
}
