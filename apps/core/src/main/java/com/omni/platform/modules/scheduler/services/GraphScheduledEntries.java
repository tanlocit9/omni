package com.omni.platform.modules.scheduler.services;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration;

/** Pure scheduled-entry selection. JobType alone cannot prove signal-preset coverage. */
public final class GraphScheduledEntries {
    private GraphScheduledEntries() {}

    public static List<UUID> select(List<JobDefinition> definitions) {
        List<JobDefinition> scheduled = definitions.stream()
                .filter(job -> Boolean.TRUE.equals(job.getIsActive())
                        && Boolean.TRUE.equals(job.getScheduleEnabled())
                        && job.getCanonicalDefinitionId() == null)
                .toList();
        return scheduled.stream()
                .filter(upstream -> scheduled.stream().noneMatch(dependent ->
                        !dependent.getId().equals(upstream.getId()) && covers(dependent, upstream)))
                .map(JobDefinition::getId)
                .sorted()
                .toList();
    }

    private static boolean covers(JobDefinition dependent, JobDefinition upstream) {
        Map<String, Object> downstreamConfig = config(dependent);
        Map<String, Object> upstreamConfig = config(upstream);
        if (!StaticTopologyDeclaration.matchesUpstream(dependent.getJobType(), downstreamConfig,
                upstream.getJobType(), upstreamConfig)) return false;
        if (upstream.getJobType() != JobType.SYNC_SIGNALS) return true;

        // Keep a standalone entry unless the evaluator covers its entire configured work.
        // Restricted requests and nested overrides need resolved atomic membership to prove coverage.
        for (String field : List.of("scope", "dateFrom", "dateTo", "parameters")) {
            if (downstreamConfig.containsKey(field) || upstreamConfig.containsKey(field)) return false;
        }
        return JobConfigMapper.toSignalsConfig(downstreamConfig).timeframe()
                        .equals(JobConfigMapper.toSignalsConfig(upstreamConfig).timeframe())
                && exchanges(downstreamConfig).containsAll(exchanges(upstreamConfig));
    }

    private static Map<String, Object> config(JobDefinition definition) {
        return definition.getConfigJson() == null ? Map.of() : definition.getConfigJson();
    }

    private static Set<String> exchanges(Map<String, Object> config) {
        List<String> values = JobConfigMapper.readStringList(config, "exchanges");
        if (values.isEmpty()) values = JobDefinitionConfig.VIETNAM_EXCHANGES;
        return values.stream().map(value -> value.strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
