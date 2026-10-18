package com.omni.platform.modules.scheduler.topology;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Version 1 static definition-path handoff. Pure and deliberately not a runtime bean.
 * Bindings and exact scope capabilities/mappings must be supplied by an approved caller.
 * A path is neither execution admission nor dataset-readiness evidence.
 */
public final class RequiredPathResolver {

    public static final int CONTRACT_VERSION = 1;
    public static final int MAX_STEPS = 1024;

    private final StaticJobTopology topology;
    private final Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> dependencies;
    private final Map<TopologyNodeKey, List<JobDefinitionIdentity>> definitions;

    public RequiredPathResolver(StaticDefinitionDependencies declaration) {
        Objects.requireNonNull(declaration, "declaration");
        topology = declaration.topology();
        dependencies = declaration.predecessors();
        Map<TopologyNodeKey, List<JobDefinitionIdentity>> byNode = new TreeMap<>();
        for (JobDefinitionIdentity identity : dependencies.keySet()) {
            byNode.computeIfAbsent(topology.nodeFor(identity), ignored -> new ArrayList<>())
                    .add(identity);
        }
        Map<TopologyNodeKey, List<JobDefinitionIdentity>> frozen = new TreeMap<>();
        byNode.forEach((node, values) -> frozen.put(node, values.stream().sorted().toList()));
        definitions = Map.copyOf(frozen);
    }

    public RequiredPath resolveRequiredPath(
            JobDefinitionIdentity target,
            Scope scope,
            Handoff handoff,
            int maxDepth,
            int maxSteps) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(handoff, "handoff");
        if (maxDepth < 0
                || maxDepth > StaticJobTopology.DEFAULT_MAX_TRAVERSAL_DEPTH
                || maxSteps <= 0
                || maxSteps > MAX_STEPS) {
            throw failure("INVALID_BOUNDS", "Depth must be 0..64 and steps 1.." + MAX_STEPS);
        }
        topology.nodeFor(target);
        validateBindings(handoff.bindings());
        Map<StepKey, Step> steps = new LinkedHashMap<>();
        visit(new StepKey(target, scope), handoff, maxDepth, maxSteps, steps, new HashMap<>());
        Map<TopologyNodeKey, Integer> ranks = new HashMap<>();
        for (int i = 0; i < topology.topologicalOrder().size(); i++) {
            ranks.put(topology.topologicalOrder().get(i), i);
        }
        List<Step> ordered =
                steps.values().stream()
                        .sorted(
                                Comparator.comparingInt((Step step) -> ranks.get(step.node()))
                                        .thenComparing(step -> step.key().definition())
                                        .thenComparing(step -> step.key().scope().type())
                                        .thenComparing(step -> step.key().scope().key())
                                        .thenComparing(step -> step.key().scope().businessDate()))
                        .toList();
        return new RequiredPath(CONTRACT_VERSION, new StepKey(target, scope), ordered);
    }

    private void visit(
            StepKey key,
            Handoff handoff,
            int remainingDepth,
            int maxSteps,
            Map<StepKey, Step> steps,
            Map<StepKey, Integer> visitedDepth) {
        if (!handoff.capabilities()
                .getOrDefault(key.definition(), Set.of())
                .contains(key.scope())) {
            throw failure("UNSUPPORTED_SCOPE", key.toString());
        }
        // Revisit on a tighter depth budget so joins cannot hide a longer path.
        Integer previous = visitedDepth.get(key);
        if (previous != null && previous <= remainingDepth) {
            return;
        }
        visitedDepth.put(key, remainingDepth);
        if (!steps.containsKey(key) && steps.size() >= maxSteps) {
            throw failure("PATH_BOUND_EXCEEDED", "Maximum steps: " + maxSteps);
        }
        steps.putIfAbsent(key, new Step(key, topology.nodeFor(key.definition()), List.of()));
        Set<StepKey> predecessors = new LinkedHashSet<>();
        List<JobDefinitionIdentity> allowed = dependencies.get(key.definition());
        for (TopologyNodeKey upstream :
                allowed.stream().map(topology::nodeFor).distinct().sorted().toList()) {
            if (remainingDepth == 0) {
                throw failure(
                        "PATH_BOUND_EXCEEDED", "Ancestor depth exceeded at " + key.definition());
            }
            List<JobDefinitionIdentity> choices = handoff.bindings().get(upstream);
            if (choices == null) {
                choices =
                        allowed.stream()
                                .filter(identity -> topology.nodeFor(identity).equals(upstream))
                                .toList();
                if (choices.size() != 1) {
                    throw failure(
                            "AMBIGUOUS_DEFINITION", "Explicit binding required for " + upstream);
                }
            }
            for (JobDefinitionIdentity definition : choices.stream().sorted().toList()) {
                if (!allowed.contains(definition)) {
                    throw failure(
                            "INVALID_BINDING",
                            "Definition is not a declared predecessor: " + definition);
                }
                Scope mapped = handoff.mappings().get(new ScopeEdge(key, definition));
                if (mapped == null) {
                    throw failure(
                            "UNSUPPORTED_SCOPE",
                            "Missing edge scope mapping: " + key + " -> " + definition);
                }
                if (!mapped.businessDate().equals(key.scope().businessDate())) {
                    throw failure("UNSUPPORTED_SCOPE", "Cross-date scope mapping is not supported");
                }
                StepKey predecessor = new StepKey(definition, mapped);
                predecessors.add(predecessor);
                visit(predecessor, handoff, remainingDepth - 1, maxSteps, steps, visitedDepth);
            }
        }
        steps.put(
                key, new Step(key, topology.nodeFor(key.definition()), List.copyOf(predecessors)));
    }

    private void validateBindings(Map<TopologyNodeKey, List<JobDefinitionIdentity>> bindings) {
        bindings.forEach(
                (node, values) -> {
                    if (!definitions.containsKey(node)
                            || values.isEmpty()
                            || Set.copyOf(values).size() != values.size()) {
                        throw failure(
                                "INVALID_BINDING", "Unknown, empty or duplicate binding: " + node);
                    }
                    for (JobDefinitionIdentity definition : values) {
                        if (!definitions.get(node).contains(definition)) {
                            throw failure(
                                    "INVALID_BINDING",
                                    "Definition does not map to " + node + ": " + definition);
                        }
                    }
                });
    }

    private static IllegalArgumentException failure(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }

    public enum ScopeType {
        GLOBAL,
        EXCHANGE,
        SECTOR,
        SYMBOL
    }

    /** Exact source-qualified scope; no normalization or implicit fan-out expansion. */
    public record Scope(ScopeType type, String key, LocalDate businessDate) {
        public Scope {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(businessDate, "businessDate");
            if (key.isBlank() || !key.equals(key.trim()) || key.length() > 256) {
                throw new IllegalArgumentException(
                        "Scope key must be nonblank, trimmed and at most 256 characters");
            }
        }
    }

    public record StepKey(JobDefinitionIdentity definition, Scope scope) {
        public StepKey {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(scope, "scope");
        }
    }

    /** Mapping direction is dependent scoped step -> upstream definition's effective scope. */
    public record ScopeEdge(StepKey dependent, JobDefinitionIdentity upstream) {
        public ScopeEdge {
            Objects.requireNonNull(dependent, "dependent");
            Objects.requireNonNull(upstream, "upstream");
        }
    }

    public record Handoff(
            Map<TopologyNodeKey, List<JobDefinitionIdentity>> bindings,
            Map<JobDefinitionIdentity, Set<Scope>> capabilities,
            Map<ScopeEdge, Scope> mappings) {
        public Handoff {
            Map<TopologyNodeKey, List<JobDefinitionIdentity>> bindingCopy = new HashMap<>();
            bindings.forEach((node, values) -> bindingCopy.put(node, List.copyOf(values)));
            bindings = Map.copyOf(bindingCopy);
            Map<JobDefinitionIdentity, Set<Scope>> capabilityCopy = new HashMap<>();
            capabilities.forEach(
                    (definition, scopes) -> capabilityCopy.put(definition, Set.copyOf(scopes)));
            capabilities = Map.copyOf(capabilityCopy);
            mappings = Map.copyOf(mappings);
        }
    }

    public record Step(StepKey key, TopologyNodeKey node, List<StepKey> predecessors) {
        public Step {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(node, "node");
            predecessors = List.copyOf(predecessors);
        }
    }

    /** Immutable definition/node path only; I3 must resolve persisted UUIDs and freeze membership. */
    public record RequiredPath(int contractVersion, StepKey target, List<Step> steps) {
        public RequiredPath {
            Objects.requireNonNull(target, "target");
            steps = List.copyOf(steps);
            if (contractVersion != CONTRACT_VERSION
                    || steps.isEmpty()
                    || steps.size() > MAX_STEPS) {
                throw new IllegalArgumentException("Invalid required-path version or size");
            }
        }
    }
}
