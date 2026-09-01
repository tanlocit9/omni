package com.omni.platform.modules.scheduler.constants;

import java.util.ArrayList;
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
        private static final String CRON_18_45_WEEKDAYS = "0 45 18 * * MON-FRI";
        private static final String CRON_19_00_WEEKDAYS = "0 0 19 * * MON-FRI";
        private static final String CRON_19_15_WEEKDAYS = "0 15 19 * * MON-FRI";
        private static final String CRON_19_30_WEEKDAYS = "0 30 19 * * MON-FRI";
        private static final String CRON_19_45_WEEKDAYS = "0 45 19 * * MON-FRI";
        private static final String CRON_03_00_MONTHLY = "0 0 3 1 * *";
        private static final int SYNC_STOCK_PRICE_START_HOUR = 18;
        private static final int SYNC_STOCK_PRICE_START_MINUTE = 0;
        private static final int PER_SECTOR_STEP_MINUTES = 2;

        // Start times for the two canonical-universe Sector Transition writers
        // (see section 4b). Kept separate from the CRON_* constants above so each
        // shared output family has an explicit schedule.
        private static final int SECTOR_TRANSITION_ANALYZE_START_HOUR = 20;
        private static final int SECTOR_TRANSITION_ANALYZE_START_MINUTE = 0;
        private static final int SECTOR_TRANSITION_EVALUATE_OUTCOMES_START_HOUR = 21;
        private static final int SECTOR_TRANSITION_EVALUATE_OUTCOMES_START_MINUTE = 0;
        private static final int SECTOR_TRANSITION_STEP_MINUTES = PER_SECTOR_STEP_MINUTES;

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
        public static final String SIGNAL_STRATEGY_ICHIMOKU_V1 = "ICHIMOKU_V1";
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

        public static final List<String> ENABLED_SECTOR_CODES = SectorSeedConfig.SECTOR_SEEDS.stream()
                        .map(SectorSeedConfig.SectorSeed::code)
                        .toList();

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

        private static final List<JobDefinitionSeed> SYNC_STOCK_PRICE_SEEDS = generateSyncStockPriceSeeds(
                        SYNC_STOCK_PRICE_START_HOUR,
                        SYNC_STOCK_PRICE_START_MINUTE,
                        PER_SECTOR_STEP_MINUTES);

        private static final List<JobDefinitionSeed> SYNC_INDICATORS_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.SYNC_INDICATORS,
                                        "Sync technical indicators",
                                        CRON_18_45_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2, CONFIG_KEY_SECTOR_CODES,
                                                                        ENABLED_SECTOR_CODES,
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_INDICATOR_SOURCE,
                                                                        CONFIG_KEY_INDICATOR_SOURCE_CLOSE,
                                                                        CONFIG_KEY_INDICATORS, SUPPORTED_INDICATORS),
                                                        List.of(JobType.SYNC_STOCK_PRICE.name()),
                                                        List.of(DATASET_EOD),
                                                        List.of(DATASET_INDICATORS))));

        private static final List<JobDefinitionSeed> SYNC_SIGNALS_SEEDS = List.of(
                        signalSeed("Sync market signals", CRON_19_00_WEEKDAYS,
                                        SIGNAL_STRATEGY_TREND_MOMENTUM_V1),
                        signalSeed("Sync Ichimoku signals", "0 5 19 * * MON-FRI",
                                        SIGNAL_STRATEGY_ICHIMOKU_V1));

        private static final List<JobDefinitionSeed> EVALUATE_SIGNALS_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.EVALUATE_SIGNALS,
                                        "Evaluate market signal outcomes - daily",
                                        CRON_19_15_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_EXCHANGES, VIETNAM_EXCHANGES,
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SIGNAL_STRATEGY,
                                                                        SIGNAL_STRATEGY_TREND_MOMENTUM_V1),
                                                        List.of(JobType.SYNC_STOCK_PRICE.name(),
                                                                        JobType.SYNC_SIGNALS.name()),
                                                        List.of(DATASET_EOD, DATASET_SIGNALS),
                                                        List.of(DATASET_SIGNAL_EVALUATIONS))));

        private static final List<JobDefinitionSeed> PRECOMPUTE_SYMBOL_FEATURES_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.PRECOMPUTE_SYMBOL_FEATURES,
                                        "Precompute Sector Wave symbol features",
                                        CRON_19_30_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                        CONFIG_KEY_SECTOR_CODES,
                                                                        ENABLED_SECTOR_CODES,
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SECTOR_WAVE_STRATEGY,
                                                                        SECTOR_WAVE_STRATEGY_V1),
                                                        List.of(JobType.SYNC_SYMBOLS.name(),
                                                                        JobType.SYNC_STOCK_PRICE.name()),
                                                        List.of(DATASET_SYMBOLS, DATASET_SECTORS, DATASET_EOD),
                                                        List.of(DATASET_SYMBOL_FEATURES))));

        private static final List<JobDefinitionSeed> PRECOMPUTE_SECTOR_FEATURES_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.PRECOMPUTE_SECTOR_FEATURES,
                                        "Precompute Sector Wave sector features - daily",
                                        CRON_19_45_WEEKDAYS,
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                        CONFIG_KEY_SECTOR_CODES,
                                                                        ENABLED_SECTOR_CODES,
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
                                        "Run Sector Wave rotation backtest - daily",
                                        "0 50 19 * * MON-FRI",
                                        configWithDependencies(
                                                        Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                        CONFIG_KEY_SECTOR_CODES,
                                                                        ENABLED_SECTOR_CODES,
                                                                        CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                        CONFIG_KEY_SECTOR_WAVE_STRATEGY,
                                                                        SECTOR_WAVE_STRATEGY_V1),
                                                        List.of(JobType.PRECOMPUTE_SECTOR_FEATURES.name(),
                                                                        JobType.SYNC_STOCK_PRICE.name()),
                                                        List.of(DATASET_SECTOR_FEATURES, DATASET_EOD),
                                                        List.of(DATASET_SECTOR_ROTATION_BACKTESTS))));

        private static JobDefinitionSeed signalSeed(String name, String cron, String strategy) {
                return new JobDefinitionSeed(
                                DataSource.ANALYZER,
                                List.of(),
                                JobType.SYNC_SIGNALS,
                                name,
                                cron,
                                configWithDependencies(
                                                Map.of(CONFIG_KEY_SECTOR_LEVEL, 2, CONFIG_KEY_SECTOR_CODES,
                                                                ENABLED_SECTOR_CODES,
                                                                CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                CONFIG_KEY_SIGNAL_STRATEGY, strategy),
                                                List.of(JobType.SYNC_STOCK_PRICE.name(),
                                                                JobType.SYNC_INDICATORS.name()),
                                                List.of(DATASET_EOD, DATASET_INDICATORS),
                                                List.of(DATASET_SIGNALS)));
        }

        // ==========================================
        // 4b. SECTOR TRANSITION SEEDS
        // ==========================================
        // Each shared Sector Transition dataset has one logical scheduled writer.
        // The job resolves and computes the complete canonical sector universe in
        // one execution instead of scheduling competing per-sector writers.
        private static final List<JobDefinitionSeed> SECTOR_TRANSITION_ANALYZE_SEEDS = generateSectorTransitionSeeds(
                        JobType.SECTOR_TRANSITION_ANALYZE,
                        SECTOR_TRANSITION_ANALYZE_START_HOUR,
                        SECTOR_TRANSITION_ANALYZE_START_MINUTE,
                        SECTOR_TRANSITION_STEP_MINUTES);

        private static final List<JobDefinitionSeed> SECTOR_TRANSITION_EVALUATE_OUTCOMES_SEEDS = generateSectorTransitionSeeds(
                        JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES,
                        SECTOR_TRANSITION_EVALUATE_OUTCOMES_START_HOUR,
                        SECTOR_TRANSITION_EVALUATE_OUTCOMES_START_MINUTE,
                        SECTOR_TRANSITION_STEP_MINUTES);

        // Automatic EOD metadata reconciliation. The cron remains stable so the
        // seeder updates the existing definition instead of creating a duplicate.
        private static final List<JobDefinitionSeed> SYNC_METADATA_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        DataSource.ANALYZER,
                                        List.of(),
                                        JobType.SYNC_METADATA,
                                        "Sync EOD dataset metadata",
                                        "0 0 20 * * MON-FRI",
                                        configWithDependencies(
                                                        Map.of("metadataType", "EOD"),
                                                        List.of(),
                                                        List.of(),
                                                        List.of())));

        public static final List<JobDefinitionSeed> BOOTSTRAP_JOB_DEFINITION_SEEDS = Stream.of(
                        SYNC_SYMBOLS_SEEDS,
                        SYNC_STOCK_PRICE_SEEDS,
                        SYNC_SIGNALS_SEEDS)
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .toList();

        public static final List<JobDefinitionSeed> DEFERRED_JOB_DEFINITION_SEEDS = Stream.of(
                        SYNC_INDICATORS_SEEDS,
                        EVALUATE_SIGNALS_SEEDS,
                        PRECOMPUTE_SYMBOL_FEATURES_SEEDS,
                        PRECOMPUTE_SECTOR_FEATURES_SEEDS,
                        SECTOR_ROTATION_BACKTEST_SEEDS,
                        SECTOR_TRANSITION_ANALYZE_SEEDS,
                        SECTOR_TRANSITION_EVALUATE_OUTCOMES_SEEDS,
                        SYNC_METADATA_SEEDS)
                        .filter(Objects::nonNull)
                        .flatMap(List::stream)
                        .toList();

        public static final List<JobDefinitionSeed> JOB_DEFINITION_SEEDS = Stream.concat(
                        BOOTSTRAP_JOB_DEFINITION_SEEDS.stream(),
                        DEFERRED_JOB_DEFINITION_SEEDS.stream())
                        .toList();

        private static List<JobDefinitionSeed> generateSyncStockPriceSeeds(int startHour, int startMinute,
                        int stepMinutes) {
                validateScheduleInputs(startHour, startMinute, stepMinutes, ENABLED_SECTOR_CODES.size());
                List<JobDefinitionSeed> seeds = new ArrayList<>();
                for (int i = 0; i < ENABLED_SECTOR_CODES.size(); i++) {
                        String sectorCode = ENABLED_SECTOR_CODES.get(i);
                        seeds.add(syncStockPriceSeed(sectorCode,
                                        weekdayCron(startHour, startMinute + i * stepMinutes)));
                }
                return List.copyOf(seeds);
        }

        private static JobDefinitionSeed syncStockPriceSeed(String sectorCode, String cronExpr) {
                return new JobDefinitionSeed(
                                PRIMARY_DATA_SOURCE,
                                FALL_BACK_DATA_SOURCES,
                                JobType.SYNC_STOCK_PRICE,
                                "Sync stock prices - " + sectorCode + " sector",
                                cronExpr,
                                configWithDependencies(
                                                Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                CONFIG_KEY_SECTOR_CODES, List.of(sectorCode)),
                                                List.of(JobType.SYNC_SYMBOLS.name()),
                                                List.of(DATASET_SYMBOLS, DATASET_SECTORS),
                                                List.of(DATASET_EOD)));
        }

        // Builds a same-day "0 M H * * MON-FRI" cron expression. Minute overflow
        // within the day is normalized; crossing 23:59 is rejected rather than wrapped.
        static String weekdayCron(int hour, int minute) {
                if (hour < 0 || hour > 23 || minute < 0) {
                        throw new IllegalArgumentException("Invalid weekday cron time: " + hour + ":" + minute);
                }
                int normalizedHour = hour + minute / 60;
                if (normalizedHour > 23) {
                        throw new IllegalArgumentException("Weekday cron schedule exceeds 23:59");
                }
                int normalizedMinute = minute % 60;
                return String.format("0 %d %d * * MON-FRI", normalizedMinute, normalizedHour);
        }

        // Generates exactly one scheduled writer for each shared Sector Transition
        // output family. The empty focus list is resolved to the full canonical
        // universe by the producer before publication.
        static List<JobDefinitionSeed> generateSectorTransitionSeeds(
                        JobType jobType,
                        int startHour,
                        int startMinute,
                        int stepMinutes) {
                validateScheduleInputs(startHour, startMinute, stepMinutes, 1);
                String cronExpr = weekdayCron(startHour, startMinute);
                JobDefinitionSeed seed = switch (jobType) {
                        case SECTOR_TRANSITION_ANALYZE -> sectorTransitionAnalyzeSeed(cronExpr);
                        case SECTOR_TRANSITION_EVALUATE_OUTCOMES -> sectorTransitionOutcomeSeed(cronExpr);
                        default -> throw new IllegalArgumentException(
                                        "Unsupported Sector Transition job type: " + jobType);
                };
                return List.of(seed);
        }

        private static void validateScheduleInputs(int startHour, int startMinute, int stepMinutes, int seedCount) {
                if (stepMinutes <= 0) {
                        throw new IllegalArgumentException("stepMinutes must be positive");
                }
                weekdayCron(startHour, startMinute);
                weekdayCron(startHour, startMinute + (seedCount - 1) * stepMinutes);
        }

        private static JobDefinitionSeed sectorTransitionAnalyzeSeed(String cronExpr) {
                return sectorTransitionSeed(
                                JobType.SECTOR_TRANSITION_ANALYZE,
                                "Run Sector Transition analysis - daily canonical universe",
                                cronExpr);
        }

        private static JobDefinitionSeed sectorTransitionOutcomeSeed(String cronExpr) {
                return sectorTransitionSeed(
                                JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES,
                                "Evaluate Sector Transition outcomes - daily canonical universe",
                                cronExpr);
        }

        private static JobDefinitionSeed sectorTransitionSeed(
                        JobType jobType,
                        String title,
                        String cronExpr) {
                return new JobDefinitionSeed(
                                DataSource.ANALYZER,
                                List.of(),
                                jobType,
                                title,
                                cronExpr,
                                configWithDependencies(
                                                Map.of(CONFIG_KEY_SECTOR_LEVEL, 2,
                                                                CONFIG_KEY_SECTOR_CODES,
                                                                ENABLED_SECTOR_CODES,
                                                                CONFIG_KEY_FOCUS_SECTOR_CODES, List.of(),
                                                                CONFIG_KEY_TIMEFRAME, INDICATOR_TIMEFRAME_1D,
                                                                CONFIG_KEY_SECTOR_TRANSITION_STRATEGY,
                                                                SECTOR_TRANSITION_STRATEGY_V1,
                                                                CONFIG_KEY_PREDICTION_HORIZONS, List.of(1, 5, 10)),
                                                sectorTransitionDependsOnJobs(jobType),
                                                sectorTransitionDependsOnDatasets(jobType),
                                                sectorTransitionProducesDatasets(jobType)));
        }

        private static List<String> sectorTransitionDependsOnJobs(JobType jobType) {
                return List.of(JobType.PRECOMPUTE_SECTOR_FEATURES.name());
        }

        private static List<String> sectorTransitionDependsOnDatasets(JobType jobType) {
                if (jobType == JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES) {
                        return List.of(DATASET_SECTOR_TRANSITION_PREDICTIONS, DATASET_SECTOR_FEATURES);
                }
                return List.of(DATASET_SECTOR_FEATURES);
        }

        // Transition rows share logical datasets. One canonical-universe job owns
        // each output family, while Analyzer merge keys preserve the sector dimension
        // without requiring separate physical dataset names.
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
