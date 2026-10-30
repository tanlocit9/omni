package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

class GraphScheduledEntriesTest {
    private JobDefinition job(JobType type, Map<String, Object> configuration) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(type);
        job.setSource(JobDefinition.DataSource.ANALYZER);
        job.setConfigJson(configuration);
        return job;
    }

    @Test
    void trendEvaluationKeepsOtherSignalStrategiesAsScheduledEntries() {
        var trend = job(JobType.SYNC_SIGNALS, Map.of("strategy", "TREND_MOMENTUM_V1"));
        var ichimoku = job(JobType.SYNC_SIGNALS, Map.of("strategy", "ICHIMOKU_V1"));
        var confirmed = job(JobType.SYNC_SIGNALS, Map.of("strategy", "CONFIRMED_TREND_EQUALS"));
        var evaluation = job(JobType.EVALUATE_SIGNALS, Map.of("strategy", "TREND_MOMENTUM_V1"));
        assertThat(GraphScheduledEntries.select(List.of(trend, ichimoku, confirmed, evaluation)))
                .containsExactlyInAnyOrder(ichimoku.getId(), confirmed.getId(), evaluation.getId());
    }

    @Test
    void canonicalSeedsHaveSevenEntriesAndKeepConfirmedIntradayReachable() {
        var catalog = JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream().map(seed -> {
            var definition = seed.toEntity();
            definition.setId(UUID.randomUUID());
            return definition;
        }).toList();
        var entries = GraphScheduledEntries.select(catalog);
        assertThat(entries).hasSize(7);
        assertThat(catalog.stream().filter(d -> entries.contains(d.getId()))
                .filter(d -> d.getJobType() == JobType.SYNC_SIGNALS)
                .map(d -> d.getConfigJson().get("strategy")))
                .containsExactlyInAnyOrder("ICHIMOKU_V1", "CONFIRMED_TREND_EQUALS");
    }

    @Test
    void onlyMatchingEvaluatorSuppressesItsSignalStrategyAndDefaultsAreNormalized() {
        var trend = job(JobType.SYNC_SIGNALS, Map.of());
        var ichimoku = job(JobType.SYNC_SIGNALS, Map.of("strategy", "ICHIMOKU_V1"));
        var evaluation = job(JobType.EVALUATE_SIGNALS, Map.of("strategy", " ichimoku_v1 "));
        assertThat(GraphScheduledEntries.select(List.of(trend, ichimoku, evaluation)))
                .containsExactlyInAnyOrder(trend.getId(), evaluation.getId());
        evaluation.setConfigJson(Map.of());
        assertThat(GraphScheduledEntries.select(List.of(trend, ichimoku, evaluation)))
                .containsExactlyInAnyOrder(ichimoku.getId(), evaluation.getId());
    }

    @Test
    void disabledInactiveOrRetiredEvaluatorCannotHideAScheduledSignal() {
        for (String state : List.of("inactive", "disabled", "retired")) {
            var signal = job(JobType.SYNC_SIGNALS, Map.of());
            var evaluation = job(JobType.EVALUATE_SIGNALS, Map.of());
            switch (state) {
                case "inactive" -> evaluation.setIsActive(false);
                case "disabled" -> evaluation.setScheduleEnabled(false);
                case "retired" -> evaluation.setCanonicalDefinitionId(UUID.randomUUID());
            }
            assertThat(GraphScheduledEntries.select(List.of(signal, evaluation)))
                    .containsExactly(signal.getId());
        }
    }

    @Test
    void narrowerTimeframeExchangeOrRequestOverrideDoesNotHideUncoveredWork() {
        var signal = job(JobType.SYNC_SIGNALS, Map.of("timeframe", "1d"));
        var restricted = List.<Map<String, Object>>of(
                Map.of("timeframe", "1h"), Map.of("exchanges", List.of("HOSE")),
                Map.of("scope", Map.of("type", "EXCHANGE", "key", "HOSE")),
                Map.of("dateFrom", "2026-10-29"),
                Map.of("parameters", Map.of("strategy", "ICHIMOKU_V1")));
        for (var configuration : restricted) {
            var evaluation = job(JobType.EVALUATE_SIGNALS, configuration);
            assertThat(GraphScheduledEntries.select(List.of(signal, evaluation)))
                    .containsExactlyInAnyOrder(signal.getId(), evaluation.getId());
        }
        signal.setConfigJson(Map.of("exchanges", List.of("hose")));
        var evaluation = job(JobType.EVALUATE_SIGNALS, Map.of("exchanges", List.of(" HOSE ")));
        assertThat(GraphScheduledEntries.select(List.of(signal, evaluation)))
                .containsExactly(evaluation.getId());
    }
}
