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

/**
 * Builds the dependency context evaluated immediately before scheduler dispatch.
 *
 * <p>Market-wide indicator jobs select symbols dynamically, so their enforced EOD
 * dependencies must be expanded to the same exact logical partitions used by the
 * producer and Analyzer consumer. No physical object-storage paths are emitted.
 */
@Component
public class JobDependencyContextFactory {

    private static final String EOD_DATASET = "eod";

    private final SymbolRepository symbolRepository;

    public JobDependencyContextFactory(SymbolRepository symbolRepository) {
        this.symbolRepository = symbolRepository;
    }

    public JobExecutionContext create(JobDefinition job) {
        return create(job, UUID.randomUUID().toString());
    }

    public JobExecutionContext create(JobDefinition job, String executionId) {
        return create(job, executionId, null, null);
    }

    public JobExecutionContext create(
            JobDefinition job, String executionId, String workType, String workKey) {
        Map<String, Object> config = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        if (job.getJobType() == JobType.PRECOMPUTE_SYMBOL_FEATURES) {
            return new JobExecutionContext(
                    job, executionId, Map.of(), List.of(eodDependency(workKey)));
        }
        if (job.getJobType() == JobType.PRECOMPUTE_SECTOR_FEATURES) {
            var wave = JobConfigMapper.toSectorWaveConfig(config);
            if (!"SECTOR".equalsIgnoreCase(workType) || workKey == null || workKey.isBlank())
                throw new IllegalArgumentException("Sector feature work requires a sector key");
            var dependencies =
                    symbolRepository.findBySectorCodesAndLevel(
                                    new String[] {workKey}, wave.filters().sectorLevel()).stream()
                            .map(symbol -> dependency("symbol-features", Map.of(
                                    "exchange", normalize(symbol.getExchange()),
                                    "code", normalize(symbol.getCode()),
                                    "timeframe", normalize(wave.timeframe()))))
                            .toList();
            return new JobExecutionContext(job, executionId, Map.of(), dependencies);
        }
        if (job.getJobType() == JobType.SECTOR_ROTATION_BACKTEST) {
            var wave = JobConfigMapper.toSectorWaveConfig(config);
            var codes = wave.filters().sectorCodes();
            var dependencies =
                    symbolRepository.findDistinctSectorCodesByLevel(
                                    codes.isEmpty() ? null : codes.toArray(new String[0]),
                                    wave.filters().sectorLevel()).stream()
                            .map(code -> dependency("sector-features", Map.of(
                                    "sector_code", normalize(code),
                                    "sector_level", String.valueOf(wave.filters().sectorLevel()),
                                    "timeframe", normalize(wave.timeframe()))))
                            .toList();
            return new JobExecutionContext(job, executionId, Map.of(), dependencies);
        }
        if (job.getJobType() != JobType.SYNC_INDICATORS) {
            return new JobExecutionContext(job, executionId, Map.of());
        }
        if ("SYMBOL".equalsIgnoreCase(workType)) {
            return new JobExecutionContext(
                    job, executionId, Map.of(), List.of(eodDependency(workKey)));
        }

        SyncIndicatorsConfig indicatorsConfig = JobConfigMapper.toIndicatorsConfig(config);
        List<String> sectorCodes = indicatorsConfig.filters().sectorCodes();
        int sectorLevel = indicatorsConfig.filters().sectorLevel();
        List<Map<String, Object>> dependencies =
                symbolRepository
                        .findBySectorCodesAndLevel(
                                sectorCodes.isEmpty() ? null : sectorCodes.toArray(new String[0]),
                                sectorLevel)
                        .stream()
                        .map(JobDependencyContextFactory::eodDependency)
                        .toList();

        return new JobExecutionContext(job, executionId, Map.of(), dependencies);
    }

    private static Map<String, Object> eodDependency(SymbolKeyProjection symbol) {
        return eodDependency(normalize(symbol.getExchange()), normalize(symbol.getCode()));
    }

    private static Map<String, Object> eodDependency(String workKey) {
        if (workKey == null) {
            throw new IllegalArgumentException("SYMBOL work key is required");
        }
        int separator = workKey.indexOf('-');
        if (separator <= 0 || separator == workKey.length() - 1) {
            throw new IllegalArgumentException("SYMBOL work key must use EXCHANGE-CODE");
        }
        return eodDependency(
                normalize(workKey.substring(0, separator)),
                normalize(workKey.substring(separator + 1)));
    }

    private static Map<String, Object> eodDependency(String exchange, String code) {
        return dependency(EOD_DATASET, Map.of("exchange", exchange, "code", code));
    }

    private static Map<String, Object> dependency(String dataset, Map<String, String> partition) {
        return Map.of(
                "dataset",
                dataset,
                "partition",
                partition,
                "conditions",
                List.of("EXISTS", "READY"),
                "mode",
                "ENFORCED");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Selected symbol has a blank EOD partition value");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
