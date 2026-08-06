package com.omni.platform.modules.scheduler.constants;

public record SyncSignalsConfig(
        SectorJobFilterConfig filters,
        String timeframe,
        String strategy) {

    public static final int DEFAULT_SIGNAL_SECTOR_LEVEL = 2;

    public SyncSignalsConfig {
        filters = filters == null
                ? new SectorJobFilterConfig(java.util.List.of(), DEFAULT_SIGNAL_SECTOR_LEVEL)
                : filters;
        timeframe = timeframe == null || timeframe.isBlank()
                ? JobDefinitionConfig.INDICATOR_TIMEFRAME_1D
                : timeframe.trim();
        strategy = strategy == null || strategy.isBlank()
                ? JobDefinitionConfig.SIGNAL_STRATEGY_TREND_MOMENTUM_V1
                : strategy.trim().toUpperCase();
    }
}
