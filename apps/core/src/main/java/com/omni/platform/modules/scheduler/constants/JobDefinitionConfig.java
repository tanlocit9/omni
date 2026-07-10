package com.omni.platform.modules.scheduler.constants;

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
        private static final String CRON_03_00_MONTHLY = "0 0 3 1 * *";

        // ==========================================
        // 3. CONFIG KEYS & VALUES
        // ==========================================
        public static final String CONFIG_KEY_SECTOR = "sector";
        public static final String CONFIG_KEY_SECTORS = "sectors";
        public static final String CONFIG_KEY_EXCHANGES = "exchanges";
        public static final String CONFIG_KEY_SYMBOL_COUNT = "symbolCount";
        public static final String CONFIG_KEY_BUCKET = "bucket";
        public static final String CONFIG_KEY_OBJECT_NAME = "objectName";
        public static final String CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION = "includeSectorClassification";
        public static final String CONFIG_KEY_SECTOR_TAXONOMY = "sectorTaxonomy";
        public static final String CONFIG_KEY_SECTOR_LEVEL = "sectorLevel";
        public static final String CONFIG_KEY_SECTOR_MAPPINGS = "sectorMappings";

        private static final String SECTOR_FINANCE = "FINANCIALS";
        private static final String SECTOR_BANK = "BANKS";
        private static final String SECTOR_REAL_ESTATE = "REAL_ESTATE";

        private static final List<String> VIETNAM_EXCHANGES = List.of("HOSE", "HNX", "UPCOM");

        // ==========================================
        // 4. SEED DEFINITIONS
        // ==========================================
        private static final List<JobDefinitionSeed> SYNC_SYMBOLS_SEEDS = List.of(new JobDefinitionSeed(
                        PRIMARY_DATA_SOURCE,
                        FALL_BACK_DATA_SOURCES,
                        JobType.SYNC_SYMBOLS,
                        "Sync symbol master list",
                        CRON_03_00_MONTHLY,
                        Map.of(
                                        CONFIG_KEY_EXCHANGES, VIETNAM_EXCHANGES,
                                        CONFIG_KEY_INCLUDE_SECTOR_CLASSIFICATION, false)));

        private static final List<JobDefinitionSeed> SYNC_STOCK_PRICE_SEEDS = List.of(
                        new JobDefinitionSeed(
                                        PRIMARY_DATA_SOURCE,
                                        FALL_BACK_DATA_SOURCES,
                                        JobType.SYNC_STOCK_PRICE,
                                        "Sync stock prices - Finance sector",
                                        CRON_18_00_WEEKDAYS,
                                        Map.of(CONFIG_KEY_SECTORS, List.of(SECTOR_FINANCE))),
                        new JobDefinitionSeed(
                                        PRIMARY_DATA_SOURCE,
                                        FALL_BACK_DATA_SOURCES,
                                        JobType.SYNC_STOCK_PRICE,
                                        "Sync stock prices - Banking sector",
                                        CRON_18_05_WEEKDAYS,
                                        Map.of(CONFIG_KEY_SECTORS, List.of(SECTOR_BANK))),
                        new JobDefinitionSeed(
                                        PRIMARY_DATA_SOURCE,
                                        FALL_BACK_DATA_SOURCES,
                                        JobType.SYNC_STOCK_PRICE,
                                        "Sync stock prices - Real estate sector",
                                        CRON_18_10_WEEKDAYS,
                                        Map.of(CONFIG_KEY_SECTORS, List.of(SECTOR_REAL_ESTATE))));

        public static final List<JobDefinitionSeed> JOB_DEFINITION_SEEDS = Stream.of(
                        SYNC_SYMBOLS_SEEDS,
                        SYNC_STOCK_PRICE_SEEDS)
                        .filter(Objects::nonNull)
                        .flatMap(e -> e.stream())
                        .toList();

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