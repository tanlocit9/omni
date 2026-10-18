package com.omni.platform.modules.scheduler.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

/** Test-only JSON baseline/fixture reader; never a production topology authority. */
final class JsonSeedDependencyFixture {
    private JsonSeedDependencyFixture() {}

    static StaticDefinitionDependencies fromSeeds(List<JobDefinitionSeed> input) {
        List<JobDefinitionSeed> seeds = List.copyOf(input);
        if (seeds.size() > RequiredPathResolver.MAX_STEPS) {
            throw new IllegalArgumentException("PATH_BOUND_EXCEEDED: Maximum definition catalog");
        }
        Map<JobDefinitionIdentity, List<JobType>> requiredTypes = new TreeMap<>();
        for (JobDefinitionSeed seed : seeds) {
            JobDefinitionIdentity definition = JobDefinitionIdentity.from(seed);
            Object raw = seed.config().get(JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES);
            if (!(raw instanceof Map<?, ?> metadata)) {
                throw new IllegalArgumentException("Missing dataDependencies for " + definition);
            }
            Object jobs = metadata.get(JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS);
            if (!(jobs instanceof List<?> values)) {
                throw new IllegalArgumentException("Missing dependsOnJobs for " + definition);
            }
            List<JobType> upstream = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof String name)) {
                    throw new IllegalArgumentException("Invalid job dependency: " + value);
                }
                try {
                    upstream.add(JobType.valueOf(name));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            "Unknown job dependency: " + name, exception);
                }
            }
            if (requiredTypes.putIfAbsent(definition, upstream) != null) {
                throw new IllegalArgumentException("Ambiguous definition mapping: " + definition);
            }
        }

        Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> edges = new TreeMap<>();
        requiredTypes.forEach(
                (definition, types) -> {
                    List<JobDefinitionIdentity> upstream = new ArrayList<>();
                    for (JobType type : types.stream().distinct().toList()) {
                        List<JobDefinitionIdentity> candidates =
                                requiredTypes.keySet().stream()
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
}
