package com.omni.platform.modules.scheduler.expansion;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.messaging.IndicatorJobMessage;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkExpansionTest {
    @Test
    void rejectsMalformedRequestObjectsAndScopeBeforePreparation() {
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of());
        for (var request : List.of(
                Map.<String, Object>of("unexpected", true),
                Map.<String, Object>of("parameters", "not-an-object"),
                Map.<String, Object>of("scope", "HOSE"),
                Map.<String, Object>of("scope", Map.of("type", "SYMBOL")),
                Map.<String, Object>of("scope", Map.of("type", "SYMBOL", "key", " ")))) {
            assertThatThrownBy(() -> WorkExpansion.resolve(definition, parent(request), monday))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void scopesAndParameterOverridesResolveWithoutMutatingPresets() {
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of("timeframe", "1d"));
        var plan = WorkExpansion.resolve(definition,
                parent(Map.of("scope", Map.of("type", "SYMBOL", "key", "hose-hpg"),
                        "parameters", Map.of("timeframe", "1h"))), monday);
        assertThat(plan.config()).containsEntry("timeframe", "1h")
                .containsEntry("_expansionScope", Map.of("type", "SYMBOL", "key", "HOSE-HPG"));
        assertThat(definition.getConfigJson()).containsExactlyEntriesOf(Map.of("timeframe", "1d"));
        assertThat(WorkExpansion.unit(JobDefinition.JobType.SYNC_SYMBOLS)).isEqualTo(WorkType.EXCHANGE);
        assertThat(WorkExpansion.unit(JobDefinition.JobType.PRECOMPUTE_SECTOR_FEATURES)).isEqualTo(WorkType.SECTOR);
        assertThat(WorkExpansion.unit(JobDefinition.JobType.SYNC_METADATA)).isEqualTo(WorkType.GLOBAL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedSnapshotsAreDeeplyImmutableAndCandidateUsesFrozenConfiguration() {
        var nested = new ArrayList<>(List.of("MA20"));
        var frozen = WorkExpansion.freeze(Map.of("nested", Map.of("values", nested)));
        nested.add("MACD");
        var values = (List<String>) ((Map<String, Object>) frozen.get("nested")).get("values");
        assertThat(values).containsExactly("MA20");
        assertThatThrownBy(() -> values.add("MACD")).isInstanceOf(UnsupportedOperationException.class);
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of("timeframe", "1h"));
        var execution = parent(Map.of());
        execution.setJob(definition);
        assertThat(WorkExpansion.candidate(execution)).isSameAs(definition);
        execution.setMetaJson(Map.of("workExpansion", Map.of("businessDate", "2026-10-29",
                "configuration", Map.of("timeframe", "1d", "source", "ANALYZER"))));
        assertThat(WorkExpansion.candidate(execution).getConfigJson()).containsEntry("timeframe", "1d");
        assertThat(definition.getConfigJson()).containsEntry("timeframe", "1h");
        assertThat(WorkExpansion.canonical(null)).isEqualTo("null");
        assertThat(WorkExpansion.canonical(true)).isEqualTo("true");
        assertThatThrownBy(() -> WorkExpansion.map("invalid")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void canonicalNumbersMatchSharedVectorsWithoutPrecisionLoss() throws Exception {
        var json = JsonMapper.builder().build();
        var vectors =
                json.readTree(
                        Path.of("../../libs/contracts/fixtures/atomic-work-numbers-v1.json")
                                .toFile());
        for (var vector : vectors) {
            var number = new BigDecimal(vector.get("input").asText());
            var expected = vector.get("canonical").asText();
            assertThat(WorkExpansion.canonical(number)).isEqualTo(expected);
            assertThat(
                            WorkExpansion.canonical(
                                    List.of(
                                            "SYNC_STOCK_PRICE",
                                            "SYMBOL",
                                            "HOSE-HPG",
                                            "2026-10-29",
                                            Map.of("number", number))))
                    .isEqualTo(
                            "[\"SYNC_STOCK_PRICE\",\"SYMBOL\",\"HOSE-HPG\",\"2026-10-29\",{\"number\":"
                                    + expected
                                    + "}]");
        }
    }

    private final Instant monday = Instant.parse("2026-11-02T03:00:00Z");

    private JobDefinition job(JobDefinition.JobType type, Map<String, Object> config) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(JobDefinition.DataSource.ANALYZER);
        job.setJobType(type);
        job.setConfigJson(config);
        return job;
    }

    private JobExecutionHistory parent(Map<String, Object> request) {
        var parent = new JobExecutionHistory();
        parent.setId(UUID.randomUUID());
        parent.setMetaJson(Map.of("expansionRequest", request));
        return parent;
    }

    @Test
    void expandsWeekdaysOnceWithoutChangingDefinitionAndFreezesDefaults() {
        var job = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of("timeframe", "1d"));
        var request =
                Map.<String, Object>of(
                        "dateFrom",
                        "2026-10-30",
                        "dateTo",
                        "2026-11-02",
                        "scope",
                        Map.of("type", "EXCHANGE", "key", "hose"));
        var plan = WorkExpansion.resolve(job, parent(request), monday);
        assertThat(plan.days())
                .containsExactly(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-02"));
        assertThat(plan.config()).containsEntry("indicatorSource", "ad_close");
        assertThat(job.getConfigJson()).containsOnlyKeys("timeframe");
        assertThatThrownBy(() -> plan.config().put("source", "VND"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defaultsUseAcceptedVietnamDateNotHostClockAndWeekendHasNoWork() {
        var job = job(JobDefinition.JobType.SYNC_SIGNALS, Map.of());
        var midnight =
                WorkExpansion.resolve(job, parent(Map.of()), Instant.parse("2026-10-28T17:01:00Z"));
        assertThat(midnight.days()).containsExactly(LocalDate.parse("2026-10-29"));
        assertThat(
                        WorkExpansion.resolve(
                                        job,
                                        parent(Map.of()),
                                        Instant.parse("2026-11-01T03:00:00Z"))
                                .days())
                .isEmpty();
    }

    @Test
    void rejectsInvalidFutureOversizedAndUnsupportedScopeRatherThanSilentlySucceeding() {
        var job = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of());
        for (var request :
                List.of(
                        Map.<String, Object>of("dateFrom", "2026-11-03", "dateTo", "2026-11-03"),
                        Map.<String, Object>of("dateFrom", "2026-11-02", "dateTo", "2026-10-30"),
                        Map.<String, Object>of("dateFrom", "2020-01-01", "dateTo", "2026-11-02"),
                        Map.<String, Object>of("scope", Map.of("type", "SECTOR", "key", "BANKS")),
                        Map.<String, Object>of("parameters", Map.of("dateFrom", "2026-10-01"))))
            assertThatThrownBy(() -> WorkExpansion.resolve(job, parent(request), monday))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                WorkExpansion.resolve(
                                        job(JobDefinition.JobType.SYNC_SYMBOLS, Map.of()),
                                        parent(
                                                Map.of(
                                                        "dateFrom",
                                                        "2026-10-30",
                                                        "dateTo",
                                                        "2026-10-30")),
                                        monday))
                .hasMessageContaining("current snapshots");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedFixtureAndFlattenedWirePreserveExecutionFieldsAndCanonicalIdentity()
            throws Exception {
        var json = JsonMapper.builder().build();
        var fixture =
                json.readTree(
                        Path.of("../../libs/contracts/fixtures/atomic-work-v1.json").toFile());
        var wire = fixture.get("message");
        Map<String, Object> parameters = json.convertValue(wire.get("parameters"), Map.class);
        String id =
                WorkExpansion.identity(
                        JobDefinition.JobType.SYNC_INDICATORS,
                        WorkType.SYMBOL,
                        "HOSE-HPG",
                        LocalDate.parse("2026-10-29"),
                        parameters);
        assertThat(id).isEqualTo(wire.get("atomicWorkId").asText());
        var command =
                new IndicatorJobMessage(
                        UUID.fromString(wire.get("jobDefinitionId").asText()),
                        UUID.fromString(wire.get("executionId").asText()),
                        UUID.fromString(wire.get("parentExecutionId").asText()),
                        "ANALYZER",
                        WorkType.SYMBOL,
                        "HOSE-HPG",
                        "HOSE-HPG",
                        "1d",
                        "ad_close",
                        List.of("MA20", "MA50", "RSI14", "MACD", "ICHIMOKU"),
                        Map.of());
        var expanded =
                new ExpandedJobMessage(
                        command, "SYNC_INDICATORS", LocalDate.parse("2026-10-29"), parameters, id);
        var serialized = json.readTree(new KafkaPublisher(null, json).serialize(expanded));
        assertThat(serialized.get("executionId").asText())
                .isEqualTo(command.executionId().toString());
        assertThat(serialized.get("symbolKey").asText()).isEqualTo("HOSE-HPG");
        assertThat(serialized.get("atomicWorkId").asText()).isEqualTo(id);
        assertThat(serialized.has("command")).isFalse();
        assertThat(
                        WorkExpansion.identity(
                                JobDefinition.JobType.SYNC_INDICATORS,
                                WorkType.SYMBOL,
                                "HOSE-HPG",
                                LocalDate.parse("2026-10-30"),
                                parameters))
                .isNotEqualTo(id);
    }
}
