package com.omni.platform.modules.scheduler.expansion;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration;
import com.omni.platform.shared.executions.WorkType;

/** I9.a planning only. No durable acquisition, attempts, readiness or writer exclusion. */
public final class GraphWorkExpansion {
    public static final int MAX_WORK = 20_000;
    public static final int MAX_EDGES = 100_000;
    public static final int MAX_EXPANSIONS = 1024;
    public static final int MAX_CANDIDATE_VISITS = 200_000;

    @FunctionalInterface
    public interface AtomicExpander {
        List<ExpandedJobMessage> expand(
                JobDefinition definition, Map<String, Object> request, Instant acceptedAt);
    }

    @FunctionalInterface
    public interface SectorMembership {
        boolean contains(String symbolKey, String sectorCode, int level);
    }

    public record Work(JobType jobType, WorkType workType, String workKey,
                       LocalDate businessDate, Map<String, Object> parameters, String id) {
        public Work {
            parameters = WorkExpansion.freeze(parameters);
            if (workType != WorkExpansion.unit(jobType)
                    || !id.equals(WorkExpansion.identity(
                            jobType, workType, workKey, businessDate, parameters)))
                throw new IllegalArgumentException("Invalid expanded work identity");
        }

        static Work from(ExpandedJobMessage message) {
            return new Work(JobType.valueOf(message.jobType()), message.workType(),
                    message.workKey(), message.businessDate(), message.parameters(),
                    message.atomicWorkId());
        }
    }

    public record Dependency(String dependent, String upstream, boolean currentSnapshot) {}

    public record Plan(Instant acceptedAt, List<String> targets, List<Work> works,
                       List<Dependency> dependencies, Map<String, List<UUID>> definitions) {
        public Plan {
            targets = List.copyOf(targets);
            works = List.copyOf(works);
            dependencies = List.copyOf(dependencies);
            Map<String, List<UUID>> copy = new TreeMap<>();
            definitions.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            definitions = Collections.unmodifiableMap(copy);
        }
    }

    private final List<JobDefinition> catalog;
    private final AtomicExpander expander;
    private final SectorMembership sectors;
    private final Instant acceptedAt;
    private final Map<String, List<Work>> expansions = new HashMap<>();
    private final Map<String, Work> works = new TreeMap<>();
    private final Map<String, Set<UUID>> definitions = new TreeMap<>();
    private final Set<Dependency> edges = new LinkedHashSet<>();
    private final Set<String> complete = new HashSet<>();
    private final Set<String> visiting = new HashSet<>();
    private int candidateVisits;
    private int expandedCandidates;

    private GraphWorkExpansion(List<JobDefinition> catalog, AtomicExpander expander,
                               SectorMembership sectors, Instant acceptedAt) {
        if (catalog.size() > MAX_EXPANSIONS)
            throw new IllegalArgumentException("GRAPH_CATALOG_BOUND_EXCEEDED");
        this.catalog = catalog.stream().filter(job -> Boolean.TRUE.equals(job.getIsActive()))
                .sorted(Comparator.comparing(job -> job.getId().toString())).toList();
        if (this.catalog.stream().map(JobDefinition::getId).distinct().count() != this.catalog.size())
            throw new IllegalArgumentException("Duplicate graph definition ID");
        this.expander = expander;
        this.sectors = sectors;
        this.acceptedAt = acceptedAt;
    }

    public static Plan resolve(UUID target, Map<String, Object> request, Instant acceptedAt,
                               List<JobDefinition> catalog, AtomicExpander expander,
                               SectorMembership sectors) {
        var graph = new GraphWorkExpansion(catalog, expander, sectors, acceptedAt);
        JobDefinition definition = graph.catalog.stream().filter(job -> job.getId().equals(target))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Inactive/unknown target"));
        List<Work> targets = graph.expand(definition, WorkExpansion.freeze(request));
        targets.forEach(work -> graph.visit(work, 0));
        Map<String, List<UUID>> bindings = new TreeMap<>();
        graph.definitions.forEach((key, ids) -> {
            if (graph.works.containsKey(key)) bindings.put(key, ids.stream().sorted().toList());
        });
        return new Plan(acceptedAt, targets.stream().map(Work::id).sorted().toList(),
                List.copyOf(graph.works.values()), graph.edges.stream().sorted(
                        Comparator.comparing(Dependency::dependent)
                                .thenComparing(Dependency::upstream)).toList(), bindings);
    }

    private List<Work> expand(JobDefinition definition, Map<String, Object> request) {
        String key = definition.getId() + ":" + WorkExpansion.canonical(request);
        if (expansions.containsKey(key)) return expansions.get(key);
        if (expansions.size() >= MAX_EXPANSIONS)
            throw new IllegalArgumentException("GRAPH_EXPANSION_BOUND_EXCEEDED");
        List<Work> result = expander.expand(definition, request, acceptedAt).stream()
                .map(Work::from).sorted(Comparator.comparing(Work::id)).toList();
        if (result.size() > WorkExpansion.MAX_WORK
                || result.stream().map(Work::id).distinct().count() != result.size())
            throw new IllegalArgumentException("Invalid atomic expansion membership");
        expandedCandidates += result.size();
        if (expandedCandidates > MAX_CANDIDATE_VISITS)
            throw new IllegalArgumentException("GRAPH_CANDIDATE_BOUND_EXCEEDED");
        for (Work work : result) {
            if (work.jobType() != definition.getJobType())
                throw new IllegalArgumentException("Expanded job type differs from definition");
            definitions.computeIfAbsent(work.id(), ignored -> new LinkedHashSet<>())
                    .add(definition.getId());
        }
        expansions.put(key, result);
        return result;
    }

    private void visit(Work work, int depth) {
        if (complete.contains(work.id())) return;
        if (depth > 32 || !visiting.add(work.id()))
            throw new IllegalArgumentException("GRAPH_DEPTH_OR_CYCLE");
        works.putIfAbsent(work.id(), work);
        if (works.size() > MAX_WORK)
            throw new IllegalArgumentException("GRAPH_WORK_BOUND_EXCEEDED");
        for (JobType type : StaticTopologyDeclaration.upstreamTypes(work.jobType(), work.parameters())) {
            boolean found = false;
            for (JobDefinition definition : catalog) {
                if (definition.getJobType() != type || !strategyMatches(work, definition)) continue;
                for (Work upstream : expand(definition, project(work, type))) {
                    if (++candidateVisits > MAX_CANDIDATE_VISITS)
                        throw new IllegalArgumentException("GRAPH_CANDIDATE_BOUND_EXCEEDED");
                    if (!matches(work, upstream)) continue;
                    found = true;
                    edges.add(new Dependency(work.id(), upstream.id(), type == JobType.SYNC_SYMBOLS));
                    if (edges.size() > MAX_EDGES)
                        throw new IllegalArgumentException("GRAPH_EDGE_BOUND_EXCEEDED");
                    visit(upstream, depth + 1);
                }
            }
            if (!found) throw new IllegalArgumentException("Missing scoped ancestor: " + type);
        }
        visiting.remove(work.id());
        complete.add(work.id());
    }

    private static boolean strategyMatches(Work dependent, JobDefinition upstream) {
        return dependent.jobType() != JobType.EVALUATE_SIGNALS
                || upstream.getJobType() != JobType.SYNC_SIGNALS
                || JobConfigMapper.toSignalsConfig(upstream.getConfigJson()).strategy()
                        .equals(dependent.parameters().get("strategy"));
    }

    private Map<String, Object> project(Work dependent, JobType upstream) {
        boolean snapshot = upstream == JobType.SYNC_SYMBOLS;
        LocalDate day = snapshot ? acceptedAt.atZone(WorkExpansion.ZONE).toLocalDate()
                : dependent.businessDate();
        Map<String, Object> parameters = new LinkedHashMap<>();
        Set<String> keys = switch (upstream) {
            case SYNC_INDICATORS -> Set.of("timeframe", "indicatorSource");
            case SYNC_SIGNALS -> Set.of("timeframe");
            case PRECOMPUTE_SYMBOL_FEATURES, PRECOMPUTE_SECTOR_FEATURES -> Set.of("timeframe");
            default -> Set.of();
        };
        for (String key : keys)
            if (dependent.parameters().containsKey(key)) parameters.put(key, dependent.parameters().get(key));
        // Source, strategy and target-only parameters remain owned by the upstream preset.
        return Map.of("dateFrom", day.toString(), "dateTo", day.toString(), "parameters", parameters);
    }

    private boolean matches(Work dependent, Work upstream) {
        if (upstream.jobType() != JobType.SYNC_SYMBOLS
                && !upstream.businessDate().equals(dependent.businessDate())) return false;
        if (dependent.workType() == WorkType.GLOBAL || upstream.workType() == WorkType.GLOBAL) return true;
        if (dependent.workType() == upstream.workType()) return dependent.workKey().equals(upstream.workKey());
        if (dependent.workType() == WorkType.SYMBOL && upstream.workType() == WorkType.EXCHANGE)
            return exchange(dependent.workKey()).equals(upstream.workKey());
        if (dependent.workType() == WorkType.EXCHANGE && upstream.workType() == WorkType.SYMBOL)
            return dependent.workKey().equals(exchange(upstream.workKey()));
        int level = ((Number) dependent.parameters().getOrDefault("sectorLevel", 2)).intValue();
        if (dependent.workType() == WorkType.SECTOR && upstream.workType() == WorkType.SYMBOL)
            return sectors.contains(upstream.workKey(), dependent.workKey(), level);
        if (dependent.workType() == WorkType.SYMBOL && upstream.workType() == WorkType.SECTOR)
            return sectors.contains(dependent.workKey(), upstream.workKey(), level);
        throw new IllegalArgumentException("Unsupported graph scope edge");
    }

    private static String exchange(String symbol) {
        int separator = symbol.indexOf('-');
        if (separator <= 0) throw new IllegalArgumentException("Invalid symbol identity");
        return symbol.substring(0, separator);
    }
}
