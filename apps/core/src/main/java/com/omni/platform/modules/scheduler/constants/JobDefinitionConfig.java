package com.omni.platform.modules.scheduler.constants;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

public class JobDefinitionConfig {

        // ==========================================
        // 1. DATA SOURCES
        // ==========================================
        private static final DataSource PRIMARY_DATA_SOURCE = DataSource.VND;
        private static final List<DataSource> FALL_BACK_DATA_SOURCES = List.of(DataSource.SSI, DataSource.FIREANT);

        // ==========================================
        // 2. CRON EXPRESSIONS
        // ==========================================
        private static final String CRON_18_00_WEEKDAYS = "0 0 18 * * MON-FRI";
        private static final String CRON_18_05_WEEKDAYS = "0 5 18 * * MON-FRI";
        private static final String CRON_18_10_WEEKDAYS = "0 10 18 * * MON-FRI";
        private static final String CRON_18_15_WEEKDAYS = "0 15 18 * * MON-FRI";
        private static final String CRON_18_20_WEEKDAYS = "0 20 18 * * MON-FRI";
        private static final String CRON_18_30_WEEKDAYS = "0 30 18 * * MON-FRI";
        private static final String CRON_18_35_WEEKDAYS = "0 35 18 * * MON-FRI";
        private static final String CRON_18_40_WEEKDAYS = "0 40 18 * * MON-FRI";
        private static final String CRON_18_45_WEEKDAYS = "0 45 18 * * MON-FRI";
        private static final String CRON_18_50_WEEKDAYS = "0 50 18 * * MON-FRI";
        private static final String CRON_18_55_WEEKDAYS = "0 55 18 * * MON-FRI";
        private static final String CRON_19_00_WEEKDAYS = "0 0 19 * * MON-FRI";
        private static final String CRON_19_05_WEEKDAYS = "0 5 19 * * MON-FRI";
        private static final String CRON_19_10_WEEKDAYS = "0 10 19 * * MON-FRI";
        private static final String CRON_19_15_WEEKDAYS = "0 15 19 * * MON-FRI";
        private static final String CRON_19_20_WEEKDAYS = "0 20 19 * * MON-FRI";
        private static final String CRON_19_25_WEEKDAYS = "0 25 19 * * MON-FRI";
        private static final String CRON_19_30_WEEKDAYS = "0 30 19 * * MON-FRI";
        private static final String CRON_19_35_WEEKDAYS = "0 35 19 * * MON-FRI";
        private static final String CRON_19_40_WEEKDAYS = "0 40 19 * * MON-FRI";
        private static final String CRON_19_45_WEEKDAYS = "0 45 19 * * MON-FRI";
        private static final String CRON_03_00_MONTHLY = "0 0 3 1 * *";

        // ==========================================
        // 3. CONFIG KEYS & VALUES
        // ==========================================
        public static final String CONFIG_KEY_SECTOR = "sector";
        public static final String CONFIG_KEY_SECTOR_CODES = "sectorCodes";
        public static final String CONFIG_KEY_FOCUS_SECTOR_CODES = "focusSectorCodes";
        public static final String CONFIG_KEY_SECTOR_TAXONOMY = "sectorTaxonomy";
        public static final String CONFIG_KEY_SECTOR_LEVEL = "sectorLevel";
        public static final String CONFIG_KEY_SECTOR_MAPPINGS = "sectorMappings";
        public static final String CONFIG_KEY_EXCHANGES = "exchanges";
        public static final String CONFIG_KEY_SYMBOL_COUNT = "symbolCount";
        public static final String CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION = "includeSectorClassification";
        public static final String CONFIG_KEY_TIMEFRAME = "timeframe";
        public static final String CONFIG_KEY_INDICATOR_SOURCE = "indicatorSource";
        public static final String CONFIG_KEY_INDICATOR_SOURCE_CLOSE = "ad_close";
        public static final String CONFIG_KEY_INDICATORS = "indicators";
        public static final String CONFIG_KEY_SIGNAL_STRATEGY = "strategy";
        public static final String CONFIG_KEY_SECTOR_WAVE_STRATEGY = "strategy";
        public static final String CONFIG_KEY_SECTOR_TRANSITION_STRATEGY = "strategy";
        public static final String CONFIG_KEY_EVALUATION_DATE = "evaluationDate";
        public static final String CONFIG_KEY_PREDICTION_HORIZONS = "predictionHorizons";
        public static final String CONFIG_KEY_DATA_DEPENDENCIES = "dataDependencies";
        public static final String CONFIG_KEY_DEPENDENCY_MODE = "dependencyMode";
        public static final String CONFIG_KEY_DEPENDS_ON_JOBS = "dependsOnJobs";
        public static final String CONFIG_KEY_DEPENDS_ON_DATASETS = "dependsOnDatasets";
        public static final String CONFIG_KEY_PRODUCES_DATASETS = "producesDatasets";
        public static final String CONFIG_VALUE_DEPENDENCY_MODE_DOCUMENTATION_ONLY = "DOCUMENTATION_ONLY";

        public static final String INDICATOR_TIMEFRAME_1D = "1d";
        public static final List<String> SUPPORTED_INDICATORS = List.of("MA20", "MA50", "RSI14", "MACD", "ICHIMOKU");
        public static final String SIGNAL_STRATEGY_TREND_MOMENTUM_V1 = "TREND_MOMENTUM_V1";
        public static final String SECTOR_WAVE_STRATEGY_V1 = "SECTOR_WAVE_V1";
        public static final String SECTOR_TRANSITION_STRATEGY_V1 = "SECTOR_TRANSITION_V1";

        private static final String DATASET_SYMBOLS = "symbols";
        private static final String DATASET_SECTORS = "sectors";
        private static final String DATASET_EOD = "eod";
        private static final String DATASET_INDICATORS = "indicators";
        private static final String DATASET_SIGNALS = "signals";
        private static final String DATASET_SIGNAL_EVALUATIONS = "signal-evaluations";
        private static final String DATASET_SYMBOL_FEATURES = "symbol-features";
        private static final String DATASET_SECTOR_FEATURES = "sector-features";
        private static final String DATASET_SECTOR_ROTATION_BACKTESTS = "sector-rotation-backtests";
        private static final String DATASET_SECTOR_TRANSITION_PREDICTIONS = "sector-transition-predictions";
        private static final String DATASET_SECTOR_TRANSITION_PROBABILITIES = "sector-transition-probabilities";
        private static final String DATASET_SECTOR_TRANSITION_DECISIONS = "sector-transition-decisions";
        private static final String DATASET_SECTOR_TRANSITION_OUTCOMES = "sector-transition-outcomes";

        private static final String SECTOR_FINANCE = "FINANCIAL_SERVICES";
        private static final String SECTOR_BANK = "BANKS";
        private static final String SECTOR_REAL_ESTATE = "REAL_ESTATE";
        private static final String SECTOR_BASIC_RESOURCES = "BASIC_RESOURCES";
        private static final String SECTOR_OIL_AND_GAS = "OIL_AND_GAS";
        private static final List<String> PRIMARY_SECTOR_TRANSITION_SECTOR_CODES = List.of(
                        SECTOR_FINANCE,
                        SECTOR_REAL_ESTATE,
                        SECTOR_BASIC_RESOURCES,
                        SECTOR_BANK,
                        SECTOR_OIL_AND_GAS);

        public static final List<String> VIETNAM_EXCHANGES = List.of("HOSE", "HNX", "UPCOM");

        // ==========================================
        // 4. SEED DEFINITIONS
        // ==========================================
        private static final List<JobDefinitionSeed> SYNC_SYMBOLS_SEEDS = List.of(new JobDefinitionSeed(
                        PRIMARY_DATA_SOURCE,
                        FALL_BACK_DATA_SOURCES,
                        JobType.SYNC_SYMBOLS,
                        "Sync symbol master list",
                        CRON_03_00_MONTHLY,
                        configWithDependencies(
                                        Map.of(
                                                        CONFIG_KEY_EXCHANGES, VIETNAM_EXCHANGES,
                                                        CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION, true),
                                        List.of(),
                                        List.of(),
                                        List.of(DATASET_SYMBOLS, DATASET_SECTORS))));

        private static final List<JobDefinitionSeed> SYNC_STOCK_PRICE_SEEDS = List.of(
                        syncStockPriceSeed(SECTOR_FINANCE, "Finance", CRON_18_00_WEEKDAYS),
                        syncStockPriceSeed(SECTOR_REAL_ESTATE, "Real estate", CRON_18_05_WEEKDAYS),
                        syncStockPriceSeed(SECTOR_BASIC_RESOURCES, "Basic resources", CRON_18_10_WEEKDAYS),
                        syncStockPriceSeed(SECTOR_BANK, "Banking", CRON_18_15_WEEKDAYS),
                        syncStockPriceSeed(SECTOR_OIL_AND_GAS, "Oil and gas", CRON_18_20_WEEKDAYS));

        private static final List<JobDefinitionSeed> SYNC_INDICATORS_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.SYNC_INDICATORS,
                                        "Sync technical indicators - daily",
                                        CRON_18_30_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2, CONFIG_KEY_SECTOR_CODES,
                                                                        List.of(SECTOR_BANK),
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_INDICATOR_SOURCE,
                                                                        CONFIG_KEY_INDICATOR_SOURCE_CLOSE,
                                                                        CONFIG_KEY_INDICATORS, SUPPORTED_INDICATORS),
                                                        List.of(JobType.SYNC_STOCK_PRICE.name()),
                                                        List.of(DATASET_EOD),
                                                        List.of(DATASET_INDICATORS))));

        private static final List<JobDefinitionSeed> SYNC_SIGNALS_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.SYNC_SIGNALS,
                                        "Sync market signals - daily BANKS",
                                        CRON_18_35_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2, CONFIG_KEY_SECTOR_CODES,
                                                                        List.of(SECTOR_BANK),
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SIGNAL_STRATEGY,
                                                                        SIGNAL_STRATEGY_TREND_MOMENTUM_V1),
                                                        List.of(JobType.SYNC_STOCK_PRICE.name(),
                                                                        JobType.SYNC_INDICATORS.name()),
                                                        List.of(DATASET_EOD, DATASET_INDICATORS),
                                                        List.of(DATASET_SIGNALS))));

        private static final List<JobDefinitionSeed> EVALUATE_SIGNALS_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.EVALUATE_SIGNALS,
                                        "Evaluate market signal outcomes - daily",
                                        CRON_18_40_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_EXCHANGES, VIETNAM_EXCHANGES,
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SIGNAL_STRATEGY,
                                                                        SIGNAL_STRATEGY_TREND_MOMENTUM_V1),
                                                        List.of(JobType.SYNC_STOCK_PRICE.name(), JobType.SYNC_SIGNALS.name()),
                                                        List.of(DATASET_EOD, DATASET_SIGNALS),
                                                        List.of(DATASET_SIGNAL_EVALUATIONS))));

        private static final List<JobDefinitionSeed> PRECOMPUTE_SYMBOL_FEATURES_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.PRECOMPUTE_SYMBOL_FEATURES,
                                        "Precompute Sector Wave symbol features - daily BANKS",
                                        CRON_18_45_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                        CONFIG_KEY_SECTOR_CODES, List.of(SECTOR_BANK),
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SECTOR_WAVE_STRATEGY,
                                                                        SECTOR_WAVE_STRATEGY_V1),
                                                        List.of(JobType.SYNC_SYMBOLS.name(), JobType.SYNC_STOCK_PRICE.name()),
                                                        List.of(DATASET_SYMBOLS, DATASET_SECTORS, DATASET_EOD),
                                                        List.of(DATASET_SYMBOL_FEATURES))));

        private static final List<JobDefinitionSeed> PRECOMPUTE_SECTOR_FEATURES_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.PRECOMPUTE_SECTOR_FEATURES,
                                        "Precompute Sector Wave sector features - daily BANKS",
                                        CRON_18_50_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                        CONFIG_KEY_SECTOR_CODES, List.of(SECTOR_BANK),
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SECTOR_WAVE_STRATEGY,
                                                                        SECTOR_WAVE_STRATEGY_V1),
                                                        List.of(JobType.PRECOMPUTE_SYMBOL_FEATURES.name()),
                                                        List.of(DATASET_SYMBOL_FEATURES),
                                                        List.of(DATASET_SECTOR_FEATURES))));

        private static final List<JobDefinitionSeed> SECTOR_ROTATION_BACKTEST_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.SECTOR_ROTATION_BACKTEST,
                                        "Run Sector Wave rotation backtest - daily BANKS",
                                        CRON_18_55_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                        CONFIG_KEY_SECTOR_CODES, List.of(SECTOR_BANK),
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SECTOR_WAVE_STRATEGY,
                                                                        SECTOR_WAVE_STRATEGY_V1),
                                                        List.of(JobType.PRECOMPUTE_SECTOR_FEATURES.name(),
                                                                        JobType.SYNC_STOCK_PRICE.name()),
                                                        List.of(DATASET_SECTOR_FEATURES, DATASET_EOD),
                                                        List.of(DATASET_SECTOR_ROTATION_BACKTESTS))));

        private static final List<JobDefinitionSeed> SECTOR_TRANSITION_ANALYZE_SEEDS = List.of(
                        sectorTransitionAnalyzeSeed(SECTOR_FINANCE, CRON_19_00_WEEKDAYS),
                        sectorTransitionAnalyzeSeed(SECTOR_REAL_ESTATE, CRON_19_05_WEEKDAYS),
                        sectorTransitionAnalyzeSeed(SECTOR_BASIC_RESOURCES, CRON_19_10_WEEKDAYS),
                        sectorTransitionAnalyzeSeed(SECTOR_BANK, CRON_19_15_WEEKDAYS),
                        sectorTransitionAnalyzeSeed(SECTOR_OIL_AND_GAS, CRON_19_20_WEEKDAYS));

        private static final List<JobDefinitionSeed> SECTOR_TRANSITION_EVALUATE_OUTCOMES_SEEDS = List.of(
                        sectorTransitionOutcomeSeed(SECTOR_FINANCE, CRON_19_25_WEEKDAYS),
                        sectorTransitionOutcomeSeed(SECTOR_REAL_ESTATE, CRON_19_30_WEEKDAYS),
                        sectorTransitionOutcomeSeed(SECTOR_BASIC_RESOURCES, CRON_19_35_WEEKDAYS),
                        sectorTransitionOutcomeSeed(SECTOR_BANK, CRON_19_40_WEEKDAYS),
                        sectorTransitionOutcomeSeed(SECTOR_OIL_AND_GAS, CRON_19_45_WEEKDAYS));

        public static final List<JobDefinitionSeed> JOB_DEFINITION_SEEDS = Stream.of(
                        SYNC_SYMBOLS_SEEDS,
                        SYNC_STOCK_PRICE_SEEDS,
                        SYNC_INDICATORS_SEEDS,
                        SYNC_SIGNALS_SEEDS,
                        EVALUATE_SIGNALS_SEEDS,
                        PRECOMPUTE_SYMBOL_FEATURES_SEEDS,
                        PRECOMPUTE_SECTOR_FEATURES_SEEDS,
                        SECTOR_ROTATION_BACKTEST_SEEDS,
                        SECTOR_TRANSITION_ANALYZE_SEEDS,
                        SECTOR_TRANSITION_EVALUATE_OUTCOMES_SEEDS)
                        .filter(Objects::nonNull)
                        .flatMap(e -> e.stream())
                        .toList();

        private static JobDefinitionSeed syncStockPriceSeed(String sectorCode, String sectorTitle, String cronExpr) {
                return new JobDefinitionSeed(
                                PRIMARY_DATA_SOURCE,
                                FALL_BACK_DATA_SOURCES,
                                JobType.SYNC_STOCK_PRICE,
                                "Sync stock prices - " + sectorTitle + " sector",
                                cronExpr,
                                configWithDependencies(
                                                Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                CONFIG_KEY_SECTOR_CODES, List.of(sectorCode)),
                                                List.of(JobType.SYNC_SYMBOLS.name()),
                                                List.of(DATASET_SYMBOLS, DATASET_SECTORS),
                                                List.of(DATASET_EOD)));
        }

        private static JobDefinitionSeed sectorTransitionAnalyzeSeed(String focusSectorCode, String cronExpr) {
                return sectorTransitionSeed(
                                JobType.SECTOR_TRANSITION_ANALYZE,
                                "Run Sector Transition analysis - daily " + focusSectorCode,
                                cronExpr,
                                focusSectorCode);
        }

        private static JobDefinitionSeed sectorTransitionOutcomeSeed(String focusSectorCode, String cronExpr) {
                return sectorTransitionSeed(
                                JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES,
                                "Evaluate Sector Transition outcomes - daily " + focusSectorCode,
                                cronExpr,
                                focusSectorCode);
        }

        private static JobDefinitionSeed sectorTransitionSeed(
                        JobType jobType,
                        String title,
                        String cronExpr,
                        String focusSectorCode) {
                return new JobDefinitionSeed(
                                DataSource.ANALYZER,
                                List.of(),
                                jobType,
                                title,
                                cronExpr,
                                configWithDependencies(
                                                Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                CONFIG_KEY_SECTOR_CODES,
                                                                PRIMARY_SECTOR_TRANSITION_SECTOR_CODES,
                                                                CONFIG_KEY_FOCUS_SECTOR_CODES, List.of(focusSectorCode),
                                                                CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                CONFIG_KEY_SECTOR_TRANSITION_STRATEGY,
                                                                SECTOR_TRANSITION_STRATEGY_V1,
                                                                CONFIG_KEY_PREDICTION_HORIZONS, List.of(1, 5, 10)),
                                                sectorTransitionDependsOnJobs(jobType),
                                                sectorTransitionDependsOnDatasets(jobType),
                                                sectorTransitionProducesDatasets(jobType)));
        }

        private static List<String> sectorTransitionDependsOnJobs(JobType jobType) {
                if (jobType == JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES) {
                        return List.of(JobType.SECTOR_TRANSITION_ANALYZE.name(), JobType.SYNC_STOCK_PRICE.name());
                }
                return List.of(JobType.PRECOMPUTE_SECTOR_FEATURES.name());
        }

        private static List<String> sectorTransitionDependsOnDatasets(JobType jobType) {
                if (jobType == JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES) {
                        return List.of(DATASET_SECTOR_TRANSITION_PREDICTIONS, DATASET_SECTOR_TRANSITION_DECISIONS,
                                        DATASET_EOD);
                }
                return List.of(DATASET_SECTOR_FEATURES);
        }

        private static List<String> sectorTransitionProducesDatasets(JobType jobType) {
                if (jobType == JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES) {
                        return List.of(DATASET_SECTOR_TRANSITION_OUTCOMES);
                }
                return List.of(DATASET_SECTOR_TRANSITION_PREDICTIONS, DATASET_SECTOR_TRANSITION_PROBABILITIES,
                                DATASET_SECTOR_TRANSITION_DECISIONS);
        }

        private static Map<String, Object> configWithDependencies(
                        Map<String, Object> config,
                        List<String> dependsOnJobs,
                        List<String> dependsOnDatasets,
                        List<String> producesDatasets) {
                Map<String, Object> merged = new LinkedHashMap<>(config);
                merged.put(CONFIG_KEY_DATA_DEPENDENCIES, Map.of(
                                CONFIG_KEY_DEPENDENCY_MODE, CONFIG_VALUE_DEPENDENCY_MODE_DOCUMENTATION_ONLY,
                                CONFIG_KEY_DEPENDS_ON_JOBS, dependsOnJobs,
                                CONFIG_KEY_DEPENDS_ON_DATASETS, dependsOnDatasets,
                                CONFIG_KEY_PRODUCES_DATASETS, producesDatasets));
                return merged;
        }

        // ==========================================
        // 5. RECORD DEFINITION
        // ==========================================
        public record JobDefinitionSeed(DataSource source, List<DataSource> fallbackSources, JobType jobType,
                        String title,
                        String cronExpr, Map<String, Object> config) {

                public JobDefinition toEntity() {
                        JobDefinition e = new JobDefinition();
                        e.setSource(source);
                        e.setFallbackSources(fallbackSources);
                        e.setTitle(title);
                        e.setJobType(jobType);
                        e.setCronExpr(cronExpr);
                        e.setIsActive(true);
                        e.setConfigJson(config);
                        return e;
                }
        }
}
