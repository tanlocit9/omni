package com.omni.platform.modules.scheduler.constants;

import java.util.List;
import java.util.Objects;

public record SyncIndicatorsConfig(
        SectorJobFilterConfig filters,
        String timeframe,
        String indicatorSource,
        List<String> indicators) {

    public SyncIndicatorsConfig {
        filters = filters == null ? SectorJobFilterConfig.defaults() : filters;
        timeframe = normalizeOrDefault(timeframe, JobDefinitionConfig.INDICATOR_TIMEFRAME_1D);
        indicatorSource = normalizeOrDefault(indicatorSource, JobDefinitionConfig.CONFIG_KEY_INDICATOR_SOURCE_CLOSE);
        indicators = normalizeIndicators(indicators);
    }

    private static String normalizeOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static List<String> normalizeIndicators(List<String> values) {
        if (values == null || values.isEmpty()) {
            return JobDefinitionConfig.SUPPORTED_INDICATORS;
        }
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toString().trim())
                .filter(value -> !value.isBlank())
                .map(String::toUpperCase)
                .toList();
        return normalized.equals(JobDefinitionConfig.SUPPORTED_INDICATORS)
                ? normalized
                : JobDefinitionConfig.SUPPORTED_INDICATORS;
    }
}
