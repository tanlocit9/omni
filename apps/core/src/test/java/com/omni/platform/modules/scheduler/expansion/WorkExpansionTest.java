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
    void currentSnapshotsIncludeWeekendsButMarketJobsDoNot() {
        var sunday = Instant.parse("2026-11-01T05:00:00Z");
        assertThat(
                        WorkExpansion.resolve(
                                        job(JobDefinition.JobType.SYNC_METADATA, Map.of()),
                                        parent(Map.of()),
                                        sunday)
                                .days())
                .containsExactly(LocalDate.parse("2026-11-01"));
        assertThat(
                        WorkExpansion.resolve(
                                        job(JobDefinition.JobType.SYNC_SYMBOLS, Map.of()),
                                        parent(Map.of()),
                                        sunday)
                                .days())
                .containsExactly(LocalDate.parse("2026-11-01"));
        assertThat(
                        WorkExpansion.resolve(
                                        job(JobDefinition.JobType.SYNC_INDICATORS, Map.of()),
                                        parent(Map.of()),
                                        sunday)
                                .days())
                .isEmpty();
    }

    @Test
    void rejectsMalformedRequestObjectsAndScopeBeforePreparation() {
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of());
        for (var request :
                List.of(
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
        var plan =
                WorkExpansion.resolve(
                        definition,
                        parent(
                                Map.of(
                                        "scope",
                                        Map.of("type", "SYMBOL", "key", "hose-hpg"),
                                        "parameters",
                                        Map.of("timeframe", "1h"))),
                        monday);
        assertThat(plan.config())
                .containsEntry("timeframe", "1h")
                .containsEntry("_expansionScope", Map.of("type", "SYMBOL", "key", "HOSE-HPG"));
        assertThat(definition.getConfigJson()).containsExactlyEntriesOf(Map.of("timeframe", "1d"));
        assertThat(WorkExpansion.unit(JobDefinition.JobType.SYNC_SYMBOLS))
                .isEqualTo(WorkType.EXCHANGE);
        assertThat(WorkExpansion.unit(JobDefinition.JobType.PRECOMPUTE_SECTOR_FEATURES))
                .isEqualTo(WorkType.SECTOR);
        assertThat(WorkExpansion.unit(JobDefinition.JobType.SYNC_METADATA))
                .isEqualTo(WorkType.GLOBAL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acceptedSnapshotsAreDeeplyImmutableAndCandidateUsesFrozenConfiguration() {
        var nested = new ArrayList<>(List.of("MA20"));
        var frozen = WorkExpansion.freeze(Map.of("nested", Map.of("values", nested)));
        nested.add("MACD");
        var values = (List<String>) ((Map<String, Object>) frozen.get("nested")).get("values");
        assertThat(values).containsExactly("MA20");
        assertThatThrownBy(() -> values.add("MACD"))
                .isInstanceOf(UnsupportedOperationException.class);
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of("timeframe", "1h"));
        var execution = parent(Map.of());
        execution.setJob(definition);
        assertThat(WorkExpansion.candidate(execution)).isSameAs(definition);
        execution.setMetaJson(
                Map.of(
                        "workExpansion",
                        Map.of(
                                "businessDate",
                                "2026-10-29",
                                "configuration",
                                Map.of("timeframe", "1d", "source", "ANALYZER"))));
        assertThat(WorkExpansion.candidate(execution).getConfigJson())
                .containsEntry("timeframe", "1d");
        assertThat(definition.getConfigJson()).containsEntry("timeframe", "1h");
        assertThat(WorkExpansion.canonical(null)).isEqualTo("null");
        assertThat(WorkExpansion.canonical(true)).isEqualTo("true");
        assertThatThrownBy(() -> WorkExpansion.map("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void symbolSelectionIntersectsExchangeAndExplicitScopeWithoutBroadeningMembership() {
        var hpg = symbol("HOSE", "HPG");
        var mbb = symbol("HOSE", "MBB");
        var bab = symbol("HNX", "BAB");
        var input = List.of(mbb, bab, hpg);
        var definition =
                job(JobDefinition.JobType.SYNC_INDICATORS, Map.of("exchanges", List.of("hose")));
        assertThat(WorkExpansion.selectSymbols(definition, input)).containsExactly(hpg, mbb);
        definition.setConfigJson(
                Map.of("_expansionScope", Map.of("type", "SYMBOL", "key", "hose-mbb")));
        assertThat(WorkExpansion.selectSymbols(definition, input)).containsExactly(mbb);
        definition.setConfigJson(
                Map.of("_expansionScope", Map.of("type", "EXCHANGE", "key", "HNX")));
        assertThat(WorkExpansion.selectSymbols(definition, input)).containsExactly(bab);
        definition.setConfigJson(Map.of("_expansionScope", Map.of("type", "GLOBAL", "key", "ALL")));
        assertThat(WorkExpansion.selectSymbols(definition, input)).containsExactly(bab, hpg, mbb);
        definition.setConfigJson(
                Map.of(
                        "exchanges",
                        List.of("HNX"),
                        "_expansionScope",
                        Map.of("type", "SYMBOL", "key", "HOSE-MBB")));
        assertThatThrownBy(() -> WorkExpansion.selectSymbols(definition, input))
                .hasMessageContaining("no active symbol");
        definition.setConfigJson(null);
        assertThat(WorkExpansion.selectSymbols(definition, input)).containsExactly(bab, hpg, mbb);
    }

    @Test
    void expansionBoundRejectsOversizedFanoutInsteadOfTruncatingIt() {
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of());
        var selected =
                java.util.stream.IntStream.rangeClosed(0, WorkExpansion.MAX_WORK)
                        .mapToObj(index -> symbol("HOSE", "S" + index))
                        .toList();
        assertThat(
                        WorkExpansion.selectSymbols(
                                definition, selected.subList(0, WorkExpansion.MAX_WORK)))
                .hasSize(WorkExpansion.MAX_WORK);
        assertThatThrownBy(() -> WorkExpansion.selectSymbols(definition, selected))
                .hasMessage("WORK_EXPANSION_BOUND_EXCEEDED");
    }

    @Test
    void refreshAndLegacySelectorsCannotChangeAnalyticalWorkIdentity() {
        var command =
                new IndicatorJobMessage(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        "ANALYZER",
                        WorkType.SYMBOL,
                        "HOSE-MBB",
                        "HOSE-MBB",
                        "1d",
                        "ad_close",
                        List.of("RSI14", "MA20", "RSI14"),
                        Map.of("requestId", "trace-only"));
        var first =
                WorkExpansion.parameters(
                        command,
                        Map.of(
                                "dataPolicy",
                                "PREFER_EXISTING",
                                "scope",
                                Map.of("type", "GLOBAL"),
                                "dateFrom",
                                "2026-10-30",
                                "force",
                                false,
                                "rerun",
                                false));
        var refreshed =
                WorkExpansion.parameters(
                        command, Map.of("dataPolicy", "REFRESH", "force", true, "rerun", true));
        assertThat(first)
                .isEqualTo(refreshed)
                .doesNotContainKeys(
                        "scope", "dateFrom", "dataPolicy", "force", "rerun", "metadata");
        assertThat(first.get("indicators")).isEqualTo(List.of("MA20", "RSI14"));
        var day = LocalDate.parse("2026-10-30");
        assertThat(
                        WorkExpansion.identity(
                                JobDefinition.JobType.SYNC_INDICATORS,
                                WorkType.SYMBOL,
                                "HOSE-MBB",
                                day,
                                first))
                .isEqualTo(
                        WorkExpansion.identity(
                                JobDefinition.JobType.SYNC_INDICATORS,
                                WorkType.SYMBOL,
                                "HOSE-MBB",
                                day,
                                refreshed));
        assertThatThrownBy(() -> first.put("dataPolicy", "REFRESH"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptedRangeIncludesItsLastAllowedDayAndRejectsOneDayBeyondTheBound() {
        var definition = job(JobDefinition.JobType.SYNC_INDICATORS, Map.of());
        var to = LocalDate.parse("2026-11-02");
        var from = to.minusDays(WorkExpansion.MAX_RANGE_DAYS - 1L);
        var plan =
                WorkExpansion.resolve(
                        definition,
                        parent(Map.of("dateFrom", from.toString(), "dateTo", to.toString())),
                        monday);
        assertThat(plan.accepted()).containsEntry("dateFrom", from.toString());
        assertThat(plan.days())
                .contains(to)
                .allMatch(
                        day ->
                                day.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                                        && day.getDayOfWeek() != java.time.DayOfWeek.SUNDAY);
        assertThatThrownBy(
                        () ->
                                WorkExpansion.resolve(
                                        definition,
                                        parent(
                                                Map.of(
                                                        "dateFrom",
                                                        from.minusDays(1).toString(),
                                                        "dateTo",
                                                        to.toString())),
                                        monday))
                .hasMessageContaining("oversized");
    }

    @Test
    void datedIntradayCompatibilityCannotExpandTheFrozenChildBackToTheParentRange() {
        var definition = job(JobDefinition.JobType.SYNC_INTRADAY_EOD, Map.of());
        var original = parent(Map.of());
        original.setMetaJson(
                Map.of(
                        "metadataTarget",
                        Map.of("startDate", "2026-10-30", "endDate", "2026-11-02")));
        assertThat(WorkExpansion.resolve(definition, original, monday).days())
                .containsExactly(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-11-02"));
        var child = WorkExpansion.parent(original, definition, LocalDate.parse("2026-10-30"));
        assertThat(child.getMetaJson())
                .containsEntry("metadataTarget", Map.of("tradingDate", "2026-10-30"));
        assertThat(WorkExpansion.workDay(child, monday)).isEqualTo(LocalDate.parse("2026-10-30"));
        assertThat(original.getMetaJson()).doesNotContainKey("workBusinessDate");
        assertThat(WorkExpansion.resolve(definition, child, monday).days())
                .containsExactly(LocalDate.parse("2026-10-30"));
        original.setMetaJson(null);
        assertThat(WorkExpansion.workDay(original, Instant.parse("2026-11-01T17:01:00Z")))
                .isEqualTo(LocalDate.parse("2026-11-02"));
    }

    private com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection symbol(
            String exchange, String code) {
        return new com.omni.platform.modules.scheduler.repositories.projections
                .SymbolKeyProjection() {
            public String getCode() {
                return code;
            }

            public String getExchange() {
                return exchange;
            }
        };
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
