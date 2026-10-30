package com.omni.platform.modules.scheduler.topology;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

/** Raw immutable declaration validated before a static topology is exposed. */
public record StaticTopologyDeclaration(
        List<TopologyNodeKey> nodes,
        List<Edge> edges,
        List<DefinitionMapping> definitionMappings) {

    /** Expansion edges use effective strategy; persisted V1 paths keep their old bindings. */
    public static List<JobType> upstreamTypes(JobType type, Map<String, Object> parameters) {
        return switch (type) {
            case SYNC_SYMBOLS, SYNC_METADATA -> List.of();
            case SYNC_STOCK_PRICE -> List.of(JobType.SYNC_SYMBOLS);
            case SYNC_INDICATORS -> List.of(JobType.SYNC_STOCK_PRICE);
            case SYNC_SIGNALS -> {
                String strategy = String.valueOf(parameters.getOrDefault("strategy",
                        JobDefinitionConfig.SIGNAL_STRATEGY_TREND_MOMENTUM_V1));
                if (JobDefinitionConfig.SIGNAL_STRATEGY_CONFIRMED_TREND_EQUALS.equals(strategy))
                    yield List.of(JobType.SYNC_STOCK_PRICE, JobType.SYNC_INDICATORS,
                            JobType.SYNC_INTRADAY_EOD);
                if (!List.of(JobDefinitionConfig.SIGNAL_STRATEGY_TREND_MOMENTUM_V1,
                        JobDefinitionConfig.SIGNAL_STRATEGY_ICHIMOKU_V1).contains(strategy))
                    throw new IllegalArgumentException("Unsupported graph signal strategy: " + strategy);
                yield List.of(JobType.SYNC_STOCK_PRICE, JobType.SYNC_INDICATORS);
            }
            case EVALUATE_SIGNALS -> List.of(JobType.SYNC_STOCK_PRICE, JobType.SYNC_SIGNALS);
            case PRECOMPUTE_SYMBOL_FEATURES, SYNC_INTRADAY_EOD ->
                    List.of(JobType.SYNC_SYMBOLS, JobType.SYNC_STOCK_PRICE);
            case PRECOMPUTE_SECTOR_FEATURES -> List.of(JobType.PRECOMPUTE_SYMBOL_FEATURES);
            case SECTOR_ROTATION_BACKTEST ->
                    List.of(JobType.PRECOMPUTE_SECTOR_FEATURES, JobType.SYNC_STOCK_PRICE);
            case SECTOR_TRANSITION_ANALYZE, SECTOR_TRANSITION_EVALUATE_OUTCOMES ->
                    List.of(JobType.PRECOMPUTE_SECTOR_FEATURES);
        };
    }

    /** Shared strategy-qualified edge rule for graph composition and scheduled-entry selection. */
    public static boolean matchesUpstream(JobType dependent, Map<String, Object> parameters,
            JobType upstream, Map<String, Object> upstreamConfig) {
        if (!upstreamTypes(dependent, parameters).contains(upstream)) return false;
        return dependent != JobType.EVALUATE_SIGNALS || upstream != JobType.SYNC_SIGNALS
                || JobConfigMapper.toSignalsConfig(upstreamConfig).strategy()
                        .equals(JobConfigMapper.toSignalsConfig(parameters).strategy());
    }

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
