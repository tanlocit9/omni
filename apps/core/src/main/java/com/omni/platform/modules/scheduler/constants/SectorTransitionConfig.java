package com.omni.platform.modules.scheduler.constants;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

public record SectorTransitionConfig(
        SectorJobFilterConfig filters,
        String timeframe,
        String strategy,
        LocalDate evaluationDate,
        List<Integer> predictionHorizons,
        List<String> focusSectorCodes) {

    public static final String DEFAULT_STRATEGY = "SECTOR_TRANSITION_V1";
    public static final List<Integer> DEFAULT_PREDICTION_HORIZONS = List.of(1, 5, 10);

    public SectorTransitionConfig {
        filters = filters == null ? SectorJobFilterConfig.defaults() : filters;
        timeframe = timeframe == null || timeframe.isBlank()
                ? JobDefinitionConfig.INDICATOR_TIMEFRAME_1D
                : timeframe.trim();
        strategy = strategy == null || strategy.isBlank()
                ? DEFAULT_STRATEGY
                : strategy.trim().toUpperCase();
            evaluationDate = evaluationDate == null ? defaultEvaluationDate() : evaluationDate;
            predictionHorizons = normalizeHorizons(predictionHorizons);
            focusSectorCodes = normalizeCodes(focusSectorCodes);
        }

    public static LocalDate defaultEvaluationDate() {
        return LocalDate.now(defaultClock());
    }

    static Clock defaultClock() {
        String zone = System.getProperty("app.scheduler.zone", System.getenv("APP_SCHEDULER_ZONE"));
        if (zone == null || zone.isBlank()) {
            zone = "Asia/Ho_Chi_Minh";
        }
        return Clock.system(ZoneId.of(zone.trim()));
    }

    private static List<Integer> normalizeHorizons(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return DEFAULT_PREDICTION_HORIZONS;
        }
        List<Integer> normalized = values.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList();
        return normalized.isEmpty() ? DEFAULT_PREDICTION_HORIZONS : normalized;
    }

    private static List<String> normalizeCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toString().trim())
                .filter(value -> !value.isBlank())
                .map(String::toUpperCase)
                .distinct()
                .toList();
    }
}
