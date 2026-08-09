package com.omni.platform.modules.scheduler.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JobConfigMapperTest {

    @Test
    void mapsStockPriceConfigWithNormalizedSectorFilters() {
        SyncStockPriceConfig config = JobConfigMapper.toStockPriceConfig(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES, List.of(" bank ", "bank", "Tech"),
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, "2"));

        assertThat(config.filters().sectorCodes()).containsExactly("BANK", "TECH");
        assertThat(config.filters().sectorLevel()).isEqualTo(2);
    }

    @Test
    void mapsIndicatorConfigWithDefaultsForInvalidValues() {
        SyncIndicatorsConfig config = JobConfigMapper.toIndicatorsConfig(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, 99,
                JobDefinitionConfig.CONFIG_KEY_TIMEFRAME, " 1d ",
                JobDefinitionConfig.CONFIG_KEY_INDICATOR_SOURCE, " ad_close ",
                JobDefinitionConfig.CONFIG_KEY_INDICATORS, List.of("MA20", "UNKNOWN")));

        assertThat(config.filters().sectorLevel()).isEqualTo(SectorJobFilterConfig.DEFAULT_SECTOR_LEVEL);
        assertThat(config.timeframe()).isEqualTo(JobDefinitionConfig.INDICATOR_TIMEFRAME_1D);
        assertThat(config.indicatorSource()).isEqualTo(JobDefinitionConfig.CONFIG_KEY_INDICATOR_SOURCE_CLOSE);
        assertThat(config.indicators()).containsExactlyElementsOf(JobDefinitionConfig.SUPPORTED_INDICATORS);
    }

    @Test
    void mapsSignalConfigWithSignalSpecificDefaults() {
        SyncSignalsConfig config = JobConfigMapper.toSignalsConfig(Map.of());

        assertThat(config.filters().sectorCodes()).isEmpty();
        assertThat(config.filters().sectorLevel()).isEqualTo(SyncSignalsConfig.DEFAULT_SIGNAL_SECTOR_LEVEL);
        assertThat(config.timeframe()).isEqualTo(JobDefinitionConfig.INDICATOR_TIMEFRAME_1D);
        assertThat(config.strategy()).isEqualTo(JobDefinitionConfig.SIGNAL_STRATEGY_TREND_MOMENTUM_V1);
    }

    @Test
    void mapsSectorTransitionConfigWithEvaluationDateAndHorizons() {
        SectorTransitionConfig config = JobConfigMapper.toSectorTransitionConfig(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES, List.of(" banks ", "real_estate"),
                JobDefinitionConfig.CONFIG_KEY_FOCUS_SECTOR_CODES, List.of(" banks ", "banks"),
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, "2",
                JobDefinitionConfig.CONFIG_KEY_TIMEFRAME, " 1d ",
                JobDefinitionConfig.CONFIG_KEY_SECTOR_TRANSITION_STRATEGY, " sector_transition_v1 ",
                JobDefinitionConfig.CONFIG_KEY_EVALUATION_DATE, "2026-08-07",
                JobDefinitionConfig.CONFIG_KEY_PREDICTION_HORIZONS, List.of("5", 1, 5, -2)));

        assertThat(config.filters().sectorCodes()).containsExactly("BANKS", "REAL_ESTATE");
        assertThat(config.focusSectorCodes()).containsExactly("BANKS");
        assertThat(config.filters().sectorLevel()).isEqualTo(2);
        assertThat(config.timeframe()).isEqualTo(JobDefinitionConfig.INDICATOR_TIMEFRAME_1D);
        assertThat(config.strategy()).isEqualTo(JobDefinitionConfig.SECTOR_TRANSITION_STRATEGY_V1);
        assertThat(config.evaluationDate()).isEqualTo(LocalDate.parse("2026-08-07"));
        assertThat(config.predictionHorizons()).containsExactly(1, 5);
    }

    @Test
    void mapsSectorTransitionConfigWithEmptyUniverseAndFocusedSeed() {
        SectorTransitionConfig config = JobConfigMapper.toSectorTransitionConfig(Map.of(
                JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES, List.of(),
                JobDefinitionConfig.CONFIG_KEY_FOCUS_SECTOR_CODES, List.of(" banks "),
                JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, 2));

        assertThat(config.filters().sectorCodes()).isEmpty();
        assertThat(config.focusSectorCodes()).containsExactly("BANKS");
        assertThat(config.filters().sectorLevel()).isEqualTo(2);
    }
}
