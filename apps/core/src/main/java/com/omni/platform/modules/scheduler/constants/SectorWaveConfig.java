package com.omni.platform.modules.scheduler.constants;

public record SectorWaveConfig(
        SectorJobFilterConfig filters,
        String timeframe,
        String strategy) {

    public static final String DEFAULT_STRATEGY = "SECTOR_WAVE_V1";

    public SectorWaveConfig {
        filters = filters == null ? SectorJobFilterConfig.defaults() : filters;
        timeframe = timeframe == null || timeframe.isBlank()
                ? JobDefinitionConfig.INDICATOR_TIMEFRAME_1D
                : timeframe.trim();
        strategy = strategy == null || strategy.isBlank()
                ? DEFAULT_STRATEGY
                : strategy.trim().toUpperCase();
    }
}
