package com.omni.platform.modules.notifications.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.notifications.configs.TelegramNotificationProperties.SignalFilterConfig;

class SignalFilterConfigTest {

    @Test
    void defaultsToConfirmedTrendEqualsWhenStrategiesIsNull() {
        SignalFilterConfig config = new SignalFilterConfig(null, null, null, null, null, null, null);

        assertThat(config.allowedStrategies()).containsExactly("CONFIRMED_TREND_EQUALS");
    }

    @Test
    void defaultsToConfirmedTrendEqualsWhenStrategiesIsEmpty() {
        SignalFilterConfig config = new SignalFilterConfig(List.of(), null, null, null, null, null, null);

        assertThat(config.allowedStrategies()).containsExactly("CONFIRMED_TREND_EQUALS");
    }

    @Test
    void defaultsToEmptyListsForOtherFilters() {
        SignalFilterConfig config = new SignalFilterConfig(null, null, null, null, null, null, null);

        assertThat(config.allowedSymbols()).isEmpty();
        assertThat(config.symbolPatterns()).isEmpty();
        assertThat(config.allowedDirections()).isEmpty();
        assertThat(config.allowedTimeframes()).isEmpty();
    }

    @Test
    void defaultsEnableNewSignalDateToTrue() {
        SignalFilterConfig config = new SignalFilterConfig(null, null, null, null, null, null, null);

        assertThat(config.enableNewSignalDate()).isTrue();
    }

    @Test
    void normalizesStrategiesToUppercase() {
        SignalFilterConfig config = new SignalFilterConfig(
                List.of("confirmed_trend_equals", "Trend_Momentum_V1", "ICHIMOKU_V1"),
                null, null, null, null, null, null);

        assertThat(config.allowedStrategies())
                .containsExactly("CONFIRMED_TREND_EQUALS", "TREND_MOMENTUM_V1", "ICHIMOKU_V1");
    }

    @Test
    void normalizesDirectionsToUppercase() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null,
                List.of("buy", "Sell", "HOLD"),
                null, null, null);

        assertThat(config.allowedDirections()).containsExactly("BUY", "SELL", "HOLD");
    }

    @Test
    void normalizesTimeframesToLowercase() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null,
                List.of("1D", "4H", "1h"),
                null, null);

        assertThat(config.allowedTimeframes()).containsExactly("1d", "4h", "1h");
    }

    @Test
    void matchesStrategyMatchesAllowedStrategy() {
        SignalFilterConfig config = new SignalFilterConfig(
                List.of("CONFIRMED_TREND_EQUALS", "TREND_MOMENTUM_V1"),
                null, null, null, null, null, null);

        assertThat(config.matchesStrategy("CONFIRMED_TREND_EQUALS")).isTrue();
        assertThat(config.matchesStrategy("TREND_MOMENTUM_V1")).isTrue();
        assertThat(config.matchesStrategy("ICHIMOKU_V1")).isFalse();
    }

    @Test
    void matchesStrategyCaseInsensitive() {
        SignalFilterConfig config = new SignalFilterConfig(
                List.of("CONFIRMED_TREND_EQUALS"),
                null, null, null, null, null, null);

        assertThat(config.matchesStrategy("confirmed_trend_equals")).isTrue();
        assertThat(config.matchesStrategy("Confirmed_Trend_Equals")).isTrue();
        assertThat(config.matchesStrategy("CONFIRMED_TREND_EQUALS")).isTrue();
    }

    @Test
    void matchesStrategyReturnsFalseForNull() {
        SignalFilterConfig config = new SignalFilterConfig(
                List.of("CONFIRMED_TREND_EQUALS"),
                null, null, null, null, null, null);

        assertThat(config.matchesStrategy(null)).isFalse();
    }

    @Test
    void matchesSymbolMatchesExactSymbol() {
        SignalFilterConfig config = new SignalFilterConfig(
                null,
                List.of("HOSE-FPT", "HOSE-VNM", "HNX-ACB"),
                null, null, null, null, null);

        assertThat(config.matchesSymbol("HOSE-FPT")).isTrue();
        assertThat(config.matchesSymbol("HOSE-VNM")).isTrue();
        assertThat(config.matchesSymbol("HNX-ACB")).isTrue();
        assertThat(config.matchesSymbol("HOSE-HPG")).isFalse();
    }

    @Test
    void matchesSymbolMatchesGlobPattern() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null,
                List.of("HOSE-*", "HNX-A*"),
                null, null, null, null);

        assertThat(config.matchesSymbol("HOSE-FPT")).isTrue();
        assertThat(config.matchesSymbol("HOSE-VNM")).isTrue();
        assertThat(config.matchesSymbol("HOSE-HPG")).isTrue();
        assertThat(config.matchesSymbol("HNX-ACB")).isTrue();
        assertThat(config.matchesSymbol("HNX-AAA")).isTrue();
        assertThat(config.matchesSymbol("HNX-BBC")).isFalse();
        assertThat(config.matchesSymbol("UPCOM-ABC")).isFalse();
    }

    @Test
    void matchesSymbolMatchesEitherExactOrPattern() {
        SignalFilterConfig config = new SignalFilterConfig(
                null,
                List.of("HOSE-FPT", "HNX-ACB"),
                List.of("HOSE-V*"),
                null, null, null, null);

        assertThat(config.matchesSymbol("HOSE-FPT")).isTrue(); // exact match
        assertThat(config.matchesSymbol("HNX-ACB")).isTrue();  // exact match
        assertThat(config.matchesSymbol("HOSE-VNM")).isTrue(); // pattern match
        assertThat(config.matchesSymbol("HOSE-VCB")).isTrue(); // pattern match
        assertThat(config.matchesSymbol("HOSE-HPG")).isFalse(); // no match
    }

    @Test
    void matchesSymbolReturnsTrueWhenNoFiltersConfigured() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, List.of(), List.of(), null, null, null, null);

        assertThat(config.matchesSymbol("HOSE-FPT")).isTrue();
        assertThat(config.matchesSymbol("ANY-SYMBOL")).isTrue();
    }

    @Test
    void matchesSymbolReturnsFalseForNull() {
        SignalFilterConfig config = new SignalFilterConfig(
                null,
                List.of("HOSE-FPT"),
                null, null, null, null, null);

        assertThat(config.matchesSymbol(null)).isFalse();
    }

    @Test
    void matchesDirectionMatchesAllowedDirection() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null,
                List.of("BUY", "SELL"),
                null, null, null);

        assertThat(config.matchesDirection("BUY")).isTrue();
        assertThat(config.matchesDirection("SELL")).isTrue();
        assertThat(config.matchesDirection("HOLD")).isFalse();
    }

    @Test
    void matchesDirectionCaseInsensitive() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null,
                List.of("BUY"),
                null, null, null);

        assertThat(config.matchesDirection("buy")).isTrue();
        assertThat(config.matchesDirection("Buy")).isTrue();
        assertThat(config.matchesDirection("BUY")).isTrue();
    }

    @Test
    void matchesDirectionReturnsTrueWhenNoFiltersConfigured() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, List.of(), null, null, null);

        assertThat(config.matchesDirection("BUY")).isTrue();
        assertThat(config.matchesDirection("SELL")).isTrue();
        assertThat(config.matchesDirection("HOLD")).isTrue();
    }

    @Test
    void matchesDirectionReturnsFalseForNull() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null,
                List.of("BUY"),
                null, null, null);

        assertThat(config.matchesDirection(null)).isFalse();
    }

    @Test
    void matchesTimeframeMatchesAllowedTimeframe() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null,
                List.of("1d", "4h"),
                null, null);

        assertThat(config.matchesTimeframe("1d")).isTrue();
        assertThat(config.matchesTimeframe("4h")).isTrue();
        assertThat(config.matchesTimeframe("1h")).isFalse();
    }

    @Test
    void matchesTimeframeCaseInsensitive() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null,
                List.of("1d"),
                null, null);

        assertThat(config.matchesTimeframe("1D")).isTrue();
        assertThat(config.matchesTimeframe("1d")).isTrue();
    }

    @Test
    void matchesTimeframeReturnsTrueWhenNoFiltersConfigured() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, List.of(), null, null);

        assertThat(config.matchesTimeframe("1d")).isTrue();
        assertThat(config.matchesTimeframe("4h")).isTrue();
    }

    @Test
    void matchesTimeframeReturnsFalseForNull() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null,
                List.of("1d"),
                null, null);

        assertThat(config.matchesTimeframe(null)).isFalse();
    }

    @Test
    void matchesScoreReturnsTrueWhenNoThresholdConfigured() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, null, null, null);

        assertThat(config.matchesScore(0.5)).isTrue();
        assertThat(config.matchesScore(0.0)).isTrue();
        assertThat(config.matchesScore(1.0)).isTrue();
    }

    @Test
    void matchesScoreMatchesScoreAboveThreshold() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, null, 0.7, null);

        assertThat(config.matchesScore(0.7)).isTrue();
        assertThat(config.matchesScore(0.8)).isTrue();
        assertThat(config.matchesScore(1.0)).isTrue();
        assertThat(config.matchesScore(0.69)).isFalse();
        assertThat(config.matchesScore(0.5)).isFalse();
    }

    @Test
    void matchesScoreHandlesNumberScore() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, null, 0.7, null);

        assertThat(config.matchesScore(Integer.valueOf(1))).isTrue();
        assertThat(config.matchesScore(Double.valueOf(0.8))).isTrue();
        assertThat(config.matchesScore(Float.valueOf(0.6f))).isFalse();
    }

    @Test
    void matchesScoreHandlesStringScore() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, null, 0.7, null);

        assertThat(config.matchesScore("0.8")).isTrue();
        assertThat(config.matchesScore("1.0")).isTrue();
        assertThat(config.matchesScore("0.6")).isFalse();
    }

    @Test
    void matchesScoreReturnsFalseForNullScore() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, null, 0.7, null);

        assertThat(config.matchesScore(null)).isFalse();
    }

    @Test
    void matchesScoreReturnsFalseForUnparseableScore() {
        SignalFilterConfig config = new SignalFilterConfig(
                null, null, null, null, null, 0.7, null);

        assertThat(config.matchesScore("not-a-number")).isFalse();
        assertThat(config.matchesScore("NaN")).isFalse();
    }
}
