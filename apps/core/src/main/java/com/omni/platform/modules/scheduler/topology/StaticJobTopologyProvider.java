package com.omni.platform.modules.scheduler.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.DefinitionMapping;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.Edge;

/** Owns the single active static job-topology declaration boundary. */
@Component
public final class StaticJobTopologyProvider {

    private final StaticJobTopology topology;

    public StaticJobTopologyProvider() {
        this(JobDefinitionConfig.JOB_DEFINITION_SEEDS);
    }

    StaticJobTopologyProvider(List<JobDefinitionSeed> seeds) {
        topology = StaticJobTopology.create(declare(seeds));
    }

    public StaticJobTopology topology() {
        return topology;
    }

    static StaticTopologyDeclaration declare(List<JobDefinitionSeed> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        List<TopologyNodeKey> nodes = seeds.stream()
                .map(JobDefinitionSeed::jobType)
                .distinct()
                .map(TopologyNodeKey::forJobType)
                .sorted()
                .toList();
        List<DefinitionMapping> mappings = seeds.stream()
                .map(seed -> new DefinitionMapping(
                        JobDefinitionIdentity.from(seed),
                        TopologyNodeKey.forJobType(seed.jobType())))
                .sorted((left, right) -> left.definition().compareTo(right.definition()))
                .toList();

        TreeSet<Edge> uniqueEdges = new TreeSet<>((left, right) -> {
            int upstreamOrder = left.upstream().compareTo(right.upstream());
            return upstreamOrder != 0
                    ? upstreamOrder
                    : left.downstream().compareTo(right.downstream());
        });
        for (JobDefinitionSeed seed : seeds) {
            TopologyNodeKey downstream = TopologyNodeKey.forJobType(seed.jobType());
            for (JobType dependency : jobDependencies(seed)) {
                uniqueEdges.add(new Edge(TopologyNodeKey.forJobType(dependency), downstream));
            }
        }
        return new StaticTopologyDeclaration(nodes, new ArrayList<>(uniqueEdges), mappings);
    }

    private static List<JobType> jobDependencies(JobDefinitionSeed seed) {
        Object rawDependencies = seed.config().get(JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES);
        if (!(rawDependencies instanceof Map<?, ?> dependencies)) {
            throw new IllegalArgumentException("Missing dataDependencies for " + JobDefinitionIdentity.from(seed));
        }
        Object rawJobs = dependencies.get(JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS);
        if (!(rawJobs instanceof List<?> jobs)) {
            throw new IllegalArgumentException("Missing dependsOnJobs for " + JobDefinitionIdentity.from(seed));
        }
        return jobs.stream().map(job -> parseJobType(seed, job)).toList();
    }

    private static JobType parseJobType(JobDefinitionSeed seed, Object rawJobType) {
        if (!(rawJobType instanceof String value)) {
            throw new IllegalArgumentException("Invalid job dependency for " + JobDefinitionIdentity.from(seed)
                    + ": " + rawJobType);
        }
        try {
            return JobType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown job dependency for " + JobDefinitionIdentity.from(seed)
                    + ": " + value, exception);
        }
    }
}
