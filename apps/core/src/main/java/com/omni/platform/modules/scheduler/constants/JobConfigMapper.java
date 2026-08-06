package com.omni.platform.modules.scheduler.constants;

import java.util.List;
import java.util.Map;

public final class JobConfigMapper {

    private JobConfigMapper() {
    }

    public static SyncStockPriceConfig toStockPriceConfig(Map<String, Object> config) {
        return new SyncStockPriceConfig(readFilters(config, SectorJobFilterConfig.DEFAULT_SECTOR_LEVEL));
    }

    public static SyncIndicatorsConfig toIndicatorsConfig(Map<String, Object> config) {
        return new SyncIndicatorsConfig(
                readFilters(config, SectorJobFilterConfig.DEFAULT_SECTOR_LEVEL),
                readString(config, JobDefinitionConfig.CONFIG_KEY_TIMEFRAME),
                readString(config, JobDefinitionConfig.CONFIG_KEY_INDICATOR_SOURCE),
                readStringList(config, JobDefinitionConfig.CONFIG_KEY_INDICATORS));
    }

    public static SyncSignalsConfig toSignalsConfig(Map<String, Object> config) {
        return new SyncSignalsConfig(
                readFilters(config, SyncSignalsConfig.DEFAULT_SIGNAL_SECTOR_LEVEL),
                readString(config, JobDefinitionConfig.CONFIG_KEY_TIMEFRAME),
                readString(config, JobDefinitionConfig.CONFIG_KEY_SIGNAL_STRATEGY));
    }

    private static SectorJobFilterConfig readFilters(Map<String, Object> config, int defaultSectorLevel) {
        return new SectorJobFilterConfig(
                readStringList(config, JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES),
                readInt(config, JobDefinitionConfig.CONFIG_KEY_SECTOR_LEVEL, defaultSectorLevel));
    }

    private static String readString(Map<String, Object> config, String key) {
        if (config == null) {
            return null;
        }
        Object value = config.get(key);
        return value == null ? null : value.toString();
    }

    private static int readInt(Map<String, Object> config, String key, int defaultValue) {
        if (config == null || !config.containsKey(key)) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exc) {
            return defaultValue;
        }
    }

    public static List<String> readStringList(Map<String, Object> config, String key) {
        if (config == null || !config.containsKey(key)) {
            return List.of();
        }
        Object value = config.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? null : item.toString())
                .toList();
    }
}
