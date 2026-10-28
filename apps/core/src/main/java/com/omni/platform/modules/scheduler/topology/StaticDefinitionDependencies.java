package com.omni.platform.modules.scheduler.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.DefinitionMapping;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.Edge;

/** Immutable definition-level declaration behind the existing topology API. Not a runtime workflow graph. */
public final class StaticDefinitionDependencies {
    private static final JobDefinitionIdentity CONFIRMED_SIGNALS =
            new JobDefinitionIdentity(
                    DataSource.ANALYZER, JobType.SYNC_SIGNALS, "0 10 19 * * MON-FRI");

    private final Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> predecessors;
    private final StaticJobTopology topology;

    public StaticDefinitionDependencies(
            Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        if (declarations.size() > RequiredPathResolver.MAX_STEPS) {
            throw new IllegalArgumentException("PATH_BOUND_EXCEEDED: Maximum definition catalog");
        }
        Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> frozen = new TreeMap<>();
        declarations.forEach(
                (definition, upstream) -> {
                    Objects.requireNonNull(definition, "definition");
                    List<JobDefinitionIdentity> values = List.copyOf(upstream);
                    if (new TreeSet<>(values).size() != values.size()) {
                        throw new IllegalArgumentException(
                                "Duplicate definition edge: " + definition);
                    }
                    frozen.put(definition, values.stream().sorted().toList());
                });
        frozen.forEach(
                (definition, upstream) ->
                        upstream.forEach(
                                identity -> {
                                    if (!frozen.containsKey(identity)) {
                                        throw new IllegalArgumentException(
                                                "Unknown upstream definition: " + identity);
                                    }
                                }));
        predecessors = Map.copyOf(frozen);
        topology = StaticJobTopology.create(declaration());
    }

    /** Canonical source-controlled edges. Seed JSON is deliberately never consulted. */
    public static StaticDefinitionDependencies canonical(List<JobDefinitionSeed> seeds) {
        Map<JobDefinitionIdentity, List<JobType>> types = new TreeMap<>();
        for (JobDefinitionSeed seed : seeds) {
            JobDefinitionIdentity definition = JobDefinitionIdentity.from(seed);
            List<JobType> upstream =
                    switch (definition.jobType()) {
                        case SYNC_SYMBOLS, SYNC_METADATA -> List.of();
                        case SYNC_STOCK_PRICE -> List.of(JobType.SYNC_SYMBOLS);
                        case SYNC_INDICATORS -> List.of(JobType.SYNC_STOCK_PRICE);
                        case SYNC_SIGNALS -> signalPredecessors(definition);
                        case EVALUATE_SIGNALS ->
                                List.of(JobType.SYNC_STOCK_PRICE, JobType.SYNC_SIGNALS);
                        case PRECOMPUTE_SYMBOL_FEATURES, SYNC_INTRADAY_EOD ->
                                List.of(JobType.SYNC_SYMBOLS, JobType.SYNC_STOCK_PRICE);
                        case PRECOMPUTE_SECTOR_FEATURES ->
                                List.of(JobType.PRECOMPUTE_SYMBOL_FEATURES);
                        case SECTOR_ROTATION_BACKTEST ->
                                List.of(
                                        JobType.PRECOMPUTE_SECTOR_FEATURES,
                                        JobType.SYNC_STOCK_PRICE);
                        case SECTOR_TRANSITION_ANALYZE, SECTOR_TRANSITION_EVALUATE_OUTCOMES ->
                                List.of(JobType.PRECOMPUTE_SECTOR_FEATURES);
                    };
            if (types.putIfAbsent(definition, upstream) != null) {
                throw new IllegalArgumentException("Ambiguous definition mapping: " + definition);
            }
        }
        return expand(types);
    }

    private static List<JobType> signalPredecessors(JobDefinitionIdentity definition) {
        if (definition.equals(CONFIRMED_SIGNALS)) {
            return List.of(
                    JobType.SYNC_STOCK_PRICE, JobType.SYNC_INDICATORS, JobType.SYNC_INTRADAY_EOD);
        }
        if (definition.source() == DataSource.ANALYZER
                && List.of("0 0 19 * * MON-FRI", "0 5 19 * * MON-FRI")
                        .contains(definition.cronExpression())) {
            return List.of(JobType.SYNC_STOCK_PRICE, JobType.SYNC_INDICATORS);
        }
        throw new IllegalArgumentException(
                "Unknown signal definition; declare its strategy edges explicitly: " + definition);
    }

    private static StaticDefinitionDependencies expand(
            Map<JobDefinitionIdentity, List<JobType>> types) {
        Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> edges = new TreeMap<>();
        types.forEach(
                (definition, required) -> {
                    List<JobDefinitionIdentity> upstream = new ArrayList<>();
                    for (JobType type : required.stream().distinct().toList()) {
                        List<JobDefinitionIdentity> candidates =
                                types.keySet().stream()
                                        .filter(identity -> identity.jobType() == type)
                                        .toList();
                        if (candidates.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Unknown upstream job type: " + type);
                        }
                        upstream.addAll(candidates);
                    }
                    edges.put(definition, upstream);
                });
        return new StaticDefinitionDependencies(edges);
    }

    public Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> predecessors() {
        return predecessors;
    }

    /** Terminal definitions, including standalone jobs, are scheduled entry targets. */
    public List<JobDefinitionIdentity> scheduledEntries() {
        var upstream =
                predecessors.values().stream()
                        .flatMap(List::stream)
                        .collect(java.util.stream.Collectors.toSet());
        return predecessors.keySet().stream()
                .filter(identity -> !upstream.contains(identity))
                .sorted()
                .toList();
    }

    public StaticJobTopology topology() {
        return topology;
    }

    StaticTopologyDeclaration declaration() {
        List<TopologyNodeKey> nodes =
                predecessors.keySet().stream()
                        .map(identity -> node(identity))
                        .distinct()
                        .sorted()
                        .toList();
        List<DefinitionMapping> mappings =
                predecessors.keySet().stream()
                        .sorted()
                        .map(identity -> new DefinitionMapping(identity, node(identity)))
                        .toList();
        TreeSet<Edge> edges =
                new TreeSet<>(
                        java.util.Comparator.comparing(Edge::upstream)
                                .thenComparing(Edge::downstream));
        predecessors.forEach(
                (dependent, upstream) ->
                        upstream.forEach(
                                identity -> edges.add(new Edge(node(identity), node(dependent)))));
        return new StaticTopologyDeclaration(nodes, List.copyOf(edges), mappings);
    }

    private static TopologyNodeKey node(JobDefinitionIdentity identity) {
        return TopologyNodeKey.forJobType(identity.jobType());
    }
}
