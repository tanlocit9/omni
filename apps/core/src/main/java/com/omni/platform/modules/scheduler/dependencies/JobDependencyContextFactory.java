package com.omni.platform.modules.scheduler.dependencies;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.SyncIndicatorsConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;

import lombok.RequiredArgsConstructor;

/**
 * Builds the dependency context evaluated immediately before scheduler dispatch.
 *
 * <p>Market-wide indicator jobs select symbols dynamically, so their enforced EOD
 * dependencies must be expanded to the same exact logical partitions used by the
 * producer and Analyzer consumer. No physical object-storage paths are emitted.
 */
@Component
@RequiredArgsConstructor
public class JobDependencyContextFactory {

    private static final String EOD_DATASET = "eod";

    private final SymbolRepository symbolRepository;

    public JobExecutionContext create(JobDefinition job) {
        return create(job, UUID.randomUUID().toString());
    }

    JobExecutionContext create(JobDefinition job, String executionId) {
        if (job.getJobType() != JobType.SYNC_INDICATORS) {
            return new JobExecutionContext(job, executionId, Map.of());
        }

        Map<String, Object> config = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        SyncIndicatorsConfig indicatorsConfig = JobConfigMapper.toIndicatorsConfig(config);
        List<String> sectorCodes = indicatorsConfig.filters().sectorCodes();
        int sectorLevel = indicatorsConfig.filters().sectorLevel();
        List<Map<String, Object>> dependencies = symbolRepository.findBySectorCodesAndLevel(
                sectorCodes.isEmpty() ? null : sectorCodes.toArray(new String[0]),
                sectorLevel).stream()
                .map(JobDependencyContextFactory::eodDependency)
                .toList();

        return new JobExecutionContext(job, executionId, Map.of(), dependencies);
    }

    private static Map<String, Object> eodDependency(SymbolKeyProjection symbol) {
        return Map.of(
                "dataset", EOD_DATASET,
                "partition", Map.of(
                        "exchange", normalize(symbol.getExchange()),
                        "code", normalize(symbol.getCode())),
                "conditions", List.of("EXISTS", "READY"),
                "mode", "ENFORCED");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Selected symbol has a blank EOD partition value");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
