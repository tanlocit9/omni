package com.omni.platform.modules.scheduler.constants;

public record SyncStockPriceConfig(
        SectorJobFilterConfig filters) {

    public SyncStockPriceConfig {
        filters = filters == null ? SectorJobFilterConfig.defaults() : filters;
    }
}
