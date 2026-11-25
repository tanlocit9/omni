package com.omni.platform.modules.scheduler.controllers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import tools.jackson.databind.json.JsonMapper;

class IndicatorBackfillControllerTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void discoversSectorsUsingTheRequestedActiveSymbolMembership(int level) {
        when(jdbc.queryForList(anyString(), eq("sectorLv" + level + "Code"), eq("ICB"), eq(level)))
                .thenReturn(List.of(Map.of("code", "BANK", "name", "Banks")));
        assertThat(api.sectors(level))
                .containsExactly(new IndicatorBackfillController.SectorOption("BANK", "Banks"));
        verifyNoInteractions(graph, definitions);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5})
    void rejectsInvalidSectorCatalogLevelBeforeQuerying(int level) {
        assertThatThrownBy(() -> api.sectors(level))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(jdbc, graph, definitions);
    }

    @Test
    void keepsAnEmptySectorCatalogEmpty() {
        when(jdbc.queryForList(anyString(), eq("sectorLv2Code"), eq("ICB"), eq(2)))
                .thenReturn(List.of());
        assertThat(api.sectors(2)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rerun", "force", "unexpected"})
    void rejectsRemovedOrUnknownHttpFields(String field) {
        assertThatThrownBy(
                        () ->
                                json.readValue(
                                        "{\"goal\":\"SYNC_SYMBOLS\",\"exchange\":\"HOSE\",\""
                                                + field
                                                + "\":true}",
                                        IndicatorBackfillController.Backfill.class))
                .hasMessageContaining("Unsupported request field");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "refresh", "RECALCULATE", "UNKNOWN"})
    void rejectsInvalidDataPolicyBeforeAdmission(String policy) {
        assertThatThrownBy(
                        () ->
                                api.create(
                                        "operator",
                                        "invalid-policy",
                                        new IndicatorBackfillController.Backfill(
                                                "SYNC_SYMBOLS",
                                                null,
                                                null,
                                                null,
                                                null,
                                                policy,
                                                null,
                                                null,
                                                "HOSE")))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(graph, definitions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hose", "HNX", "UPCOM"})
    void discoversAndAcceptsCurrentSymbolsExchangeSnapshotWithoutSectorOrDates(String exchange) {
        var target = definition("00000000-0000-0000-0000-000000000012", JobType.SYNC_SYMBOLS);
        when(definitions.findAll()).thenReturn(List.of(target));
        assertThat(api.goals())
                .containsExactly(
                        new IndicatorBackfillController.Goal(
                                "SYNC_SYMBOLS",
                                List.of(
                                        new IndicatorBackfillController.Target(
                                                target.getId(), null))));
        var execution = UUID.randomUUID();
        when(graph.prepareBackfill(any(), eq(target.getId()), any(), any())).thenReturn(execution);
        when(graph.requestId(execution)).thenReturn(UUID.randomUUID());
        var result =
                api.create(
                        "operator",
                        "symbols-" + exchange,
                        new IndicatorBackfillController.Backfill(
                                "SYNC_SYMBOLS",
                                null,
                                null,
                                null,
                                null,
                                "PREFER_EXISTING",
                                target.getId(),
                                null,
                                exchange));
        assertThat(result.get("goal")).isEqualTo("SYNC_SYMBOLS");
        var request = ArgumentCaptor.forClass(JobRunSnapshot.Request.class);
        verify(graph).prepareBackfill(request.capture(), eq(target.getId()), any(), any());
        var expansion = json.readTree(request.getValue().parametersJson());
        assertThat(expansion.path("scope").path("type").asText()).isEqualTo("EXCHANGE");
        assertThat(expansion.path("scope").path("key").asText())
                .isEqualTo(exchange.toUpperCase(java.util.Locale.ROOT));
        assertThat(expansion.path("dateFrom").asText())
                .isEqualTo(
                        LocalDate.now(
                                        com.omni.platform.modules.scheduler.expansion.WorkExpansion
                                                .ZONE)
                                .toString());
        assertThat(expansion.has("sectorCodes")).isFalse();
        assertThat(expansion.has("sectorLevel")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "history", "future", "sector", "symbol"})
    void rejectsAmbiguousOrHistoricalSymbolsScopeBeforePreparingWork(String scenario) {
        var today = LocalDate.now(com.omni.platform.modules.scheduler.expansion.WorkExpansion.ZONE);
        var date =
                scenario.equals("history")
                        ? today.minusDays(1)
                        : scenario.equals("future") ? today.plusDays(1) : today;
        var exchange =
                scenario.equals("missing") ? null : scenario.equals("unknown") ? "OTHER" : "HOSE";
        assertThatThrownBy(
                        () ->
                                api.create(
                                        "operator",
                                        "bad-symbols",
                                        new IndicatorBackfillController.Backfill(
                                                "SYNC_SYMBOLS",
                                                scenario.equals("sector") ? List.of("BANKS") : null,
                                                null,
                                                date,
                                                date,
                                                "PREFER_EXISTING",
                                                null,
                                                scenario.equals("symbol") ? "HOSE-MBB" : null,
                                                exchange)))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(graph, definitions);
    }

    @Test
    void freezesNormalizedOptionalSymbolScopeWithoutChangingAnalyticalParameters() {
        var target = definition("00000000-0000-0000-0000-000000000011", JobType.SYNC_SIGNALS);
        when(definitions.findAll()).thenReturn(List.of(target));
        var execution = UUID.randomUUID();
        when(graph.prepareBackfill(any(), eq(target.getId()), any(), any())).thenReturn(execution);
        when(graph.requestId(execution)).thenReturn(UUID.randomUUID());
        api.create(
                "operator",
                "narrow",
                new IndicatorBackfillController.Backfill(
                        "SYNC_SIGNALS",
                        List.of("BANKS"),
                        1,
                        day,
                        day,
                        "PREFER_EXISTING",
                        target.getId(),
                        "hose-mbb",
                        null));
        var request = ArgumentCaptor.forClass(JobRunSnapshot.Request.class);
        verify(graph)
                .prepareBackfill(
                        request.capture(),
                        eq(target.getId()),
                        any(),
                        eq(Map.of("dataPolicy", "PREFER_EXISTING")));
        var expansion = json.readTree(request.getValue().parametersJson());
        assertThat(expansion.path("scope").path("type").asText()).isEqualTo("SYMBOL");
        assertThat(expansion.path("scope").path("key").asText()).isEqualTo("HOSE-MBB");
        assertThat(expansion.path("parameters").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MBB", "HOSE-MBB/other", "s3://bucket/key", " HOSE-MBB", "HOSE-"})
    void rejectsMalformedSymbolScopeBeforePreparingWork(String symbol) {
        assertThatThrownBy(
                        () ->
                                api.create(
                                        "operator",
                                        "invalid-symbol",
                                        new IndicatorBackfillController.Backfill(
                                                "SYNC_SIGNALS",
                                                List.of("BANKS"),
                                                1,
                                                day,
                                                day,
                                                "PREFER_EXISTING",
                                                null,
                                                symbol,
                                                null)))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
        verifyNoInteractions(graph, definitions);
    }

    @Test
    void discoversOnlySupportedActiveCanonicalTargetsWithoutRequiringCron() {
        var indicator = definition("00000000-0000-0000-0000-000000000001", JobType.SYNC_INDICATORS);
        indicator.setTitle("Indicators");
        indicator.setScheduleEnabled(false);
        var first = definition("00000000-0000-0000-0000-000000000002", JobType.SYNC_SIGNALS);
        var second = definition("00000000-0000-0000-0000-000000000003", JobType.SYNC_SIGNALS);
        var inactive = definition("00000000-0000-0000-0000-000000000004", JobType.SYNC_INDICATORS);
        inactive.setIsActive(false);
        var alias = definition("00000000-0000-0000-0000-000000000005", JobType.SYNC_SIGNALS);
        alias.setCanonicalDefinitionId(first.getId());
        var unsupported = definition("00000000-0000-0000-0000-000000000006", JobType.SYNC_METADATA);
        var unknownActive =
                definition("00000000-0000-0000-0000-000000000007", JobType.SYNC_SIGNALS);
        unknownActive.setIsActive(null);
        when(definitions.findAll())
                .thenReturn(
                        List.of(
                                second,
                                inactive,
                                alias,
                                unsupported,
                                first,
                                unknownActive,
                                indicator));

        assertThat(api.goals())
                .containsExactly(
                        new IndicatorBackfillController.Goal(
                                "SYNC_INDICATORS",
                                List.of(
                                        new IndicatorBackfillController.Target(
                                                indicator.getId(), "Indicators"))),
                        new IndicatorBackfillController.Goal(
                                "SYNC_SIGNALS",
                                List.of(
                                        new IndicatorBackfillController.Target(first.getId(), null),
                                        new IndicatorBackfillController.Target(
                                                second.getId(), null))));
        verifyNoInteractions(graph);

        UUID execution = UUID.randomUUID();
        UUID selected = api.goals().get(1).targets().get(1).targetDefinitionId();
        when(graph.prepareBackfill(any(), eq(selected), any(), any())).thenReturn(execution);
        when(graph.requestId(execution)).thenReturn(UUID.randomUUID());
        assertThat(
                        api.create(
                                "operator",
                                "discovered-target",
                                new IndicatorBackfillController.Backfill(
                                        "SYNC_SIGNALS",
                                        List.of("BANK"),
                                        2,
                                        day,
                                        day,
                                        "PREFER_EXISTING",
                                        selected,
                                        null,
                                        null)))
                .containsEntry("executionId", execution);
    }

    @Test
    void omitsGoalsWithoutEligibleDefinitions() {
        when(definitions.findAll()).thenReturn(List.of());
        assertThat(api.goals()).isEmpty();
        var signal = definition("00000000-0000-0000-0000-000000000001", JobType.SYNC_SIGNALS);
        when(definitions.findAll()).thenReturn(List.of(signal));
        assertThat(api.goals())
                .extracting(IndicatorBackfillController.Goal::goal)
                .containsExactly("SYNC_SIGNALS");
        verifyNoInteractions(graph);
    }

    private static JobDefinition definition(String id, JobType type) {
        var definition = new JobDefinition();
        definition.setId(UUID.fromString(id));
        definition.setJobType(type);
        definition.setIsActive(true);
        return definition;
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "selected", "ambiguous", "wrongType"})
    void signalsGoalSelectsCanonicalStrategyExplicitly(String scenario) {
        var first = new JobDefinition();
        first.setId(UUID.randomUUID());
        first.setJobType(JobType.SYNC_SIGNALS);
        first.setIsActive(true);
        var second = new JobDefinition();
        second.setId(UUID.randomUUID());
        second.setJobType(JobType.SYNC_SIGNALS);
        second.setIsActive(true);
        var indicator = new JobDefinition();
        indicator.setId(UUID.randomUUID());
        indicator.setJobType(JobType.SYNC_INDICATORS);
        indicator.setIsActive(true);
        when(definitions.findAll())
                .thenReturn(
                        scenario.equals("single")
                                ? List.of(first, indicator)
                                : List.of(first, second, indicator));
        UUID selected =
                scenario.equals("selected")
                        ? second.getId()
                        : scenario.equals("wrongType") ? indicator.getId() : null;
        var input =
                new IndicatorBackfillController.Backfill(
                        "SYNC_SIGNALS",
                        List.of("BANK"),
                        2,
                        day,
                        day,
                        "PREFER_EXISTING",
                        selected,
                        null,
                        null);
        if (scenario.equals("ambiguous") || scenario.equals("wrongType")) {
            assertThatThrownBy(() -> api.create("operator", "signal-key", input))
                    .isInstanceOfSatisfying(
                            org.springframework.web.server.ResponseStatusException.class,
                            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
            verifyNoInteractions(graph);
        } else {
            UUID execution = UUID.randomUUID();
            UUID target = selected == null ? first.getId() : selected;
            when(graph.prepareBackfill(any(), eq(target), any(), any())).thenReturn(execution);
            when(graph.requestId(execution)).thenReturn(UUID.randomUUID());
            assertThat(api.create("operator", "signal-key", input))
                    .containsEntry("goal", "SYNC_SIGNALS");
            verify(graph).prepareBackfill(any(), eq(target), any(), any());
        }
    }

    private final GraphWorkRequestService graph = mock(GraphWorkRequestService.class);
    private final JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc =
            mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final IndicatorBackfillController api =
            new IndicatorBackfillController(graph, definitions, json, jdbc);
    private final LocalDate day = LocalDate.of(2026, 9, 1);

    @ParameterizedTest
    @ValueSource(strings = {"default", "PREFER_EXISTING", "REFRESH"})
    void acceptsOnlyTheIndicatorsGoalWithNormalizedFrozenScope(String policy) {
        String requestedPolicy = policy.equals("default") ? null : policy;
        String acceptedPolicy = requestedPolicy == null ? "PREFER_EXISTING" : requestedPolicy;
        var definition = new JobDefinition();
        definition.setId(UUID.randomUUID());
        definition.setJobType(JobType.SYNC_INDICATORS);
        definition.setIsActive(true);
        var wrongType = new JobDefinition();
        wrongType.setId(UUID.randomUUID());
        wrongType.setJobType(JobType.SYNC_SYMBOLS);
        var inactive = new JobDefinition();
        inactive.setJobType(JobType.SYNC_INDICATORS);
        inactive.setIsActive(false);
        var alias = new JobDefinition();
        alias.setJobType(JobType.SYNC_INDICATORS);
        alias.setIsActive(true);
        alias.setCanonicalDefinitionId(definition.getId());
        when(definitions.findAll()).thenReturn(List.of(definition, wrongType, inactive, alias));
        UUID execution = UUID.randomUUID(), request = UUID.randomUUID();
        when(graph.prepareBackfill(any(), eq(definition.getId()), any(), any()))
                .thenReturn(execution);
        when(graph.requestId(execution)).thenReturn(request);
        var result =
                api.create(
                        "operator",
                        "backfill-1",
                        new IndicatorBackfillController.Backfill(
                                "SYNC_INDICATORS",
                                List.of("bank", "BANK"),
                                2,
                                day,
                                day,
                                requestedPolicy,
                                null,
                                null,
                                null));
        assertThat(result)
                .containsEntry("executionId", execution)
                .containsEntry("requestId", request);
        assertThat(result)
                .containsEntry("dataPolicy", acceptedPolicy)
                .doesNotContainKeys("force", "rerun");
        var captured = ArgumentCaptor.forClass(JobRunSnapshot.Request.class);
        verify(graph)
                .prepareBackfill(
                        captured.capture(),
                        eq(definition.getId()),
                        any(),
                        eq(Map.of("dataPolicy", acceptedPolicy)));
        assertThat(captured.getValue().mode()).isEqualTo(JobRunSnapshot.Mode.BACKFILL);
        var payload = json.readTree(captured.getValue().parametersJson());
        assertThat(payload.get("sectorCodes").size()).isEqualTo(1);
        assertThat(payload.get("dataPolicy").asText()).isEqualTo(acceptedPolicy);
        assertThat(payload.has("force") || payload.has("rerun")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "goal",
                "date",
                "end",
                "level",
                "highLevel",
                "noLevel",
                "sectors",
                "noSectors",
                "manySectors",
                "code",
                "nullCode",
                "null"
            })
    void rejectsUnboundedOrInvalidScopeBeforeCreatingWork(String invalid) {
        var input =
                new IndicatorBackfillController.Backfill(
                        invalid.equals("goal") ? "EVALUATE_SIGNALS" : "SYNC_INDICATORS",
                        invalid.equals("noSectors")
                                ? null
                                : invalid.equals("manySectors")
                                        ? java.util.Collections.nCopies(101, "BANK")
                                        : invalid.equals("sectors")
                                                ? List.of()
                                                : java.util.Collections.singletonList(
                                                        invalid.equals("nullCode")
                                                                ? null
                                                                : invalid.equals("code")
                                                                        ? "../x"
                                                                        : "BANK"),
                        invalid.equals("noLevel")
                                ? null
                                : Integer.valueOf(
                                        invalid.equals("level")
                                                ? 0
                                                : invalid.equals("highLevel") ? 5 : 2),
                        invalid.equals("date") ? null : day,
                        invalid.equals("end") ? null : day,
                        "PREFER_EXISTING",
                        null,
                        null,
                        null);
        assertThatThrownBy(
                        () -> api.create("operator", "key", invalid.equals("null") ? null : input))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(graph, definitions);
    }

    @Test
    void refusesAmbiguousOrAbsentCanonicalTarget() {
        when(definitions.findAll()).thenReturn(List.of());
        assertThatThrownBy(
                        () ->
                                api.create(
                                        "operator",
                                        "key",
                                        new IndicatorBackfillController.Backfill(
                                                "SYNC_INDICATORS",
                                                List.of("BANK"),
                                                2,
                                                day,
                                                day,
                                                "PREFER_EXISTING",
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(graph);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "invalid", "conflict"})
    void translatesAdmissionFailuresWithoutReportingAccepted(String failure) {
        var definition = new JobDefinition();
        definition.setId(UUID.randomUUID());
        definition.setJobType(JobType.SYNC_INDICATORS);
        definition.setIsActive(true);
        when(definitions.findAll()).thenReturn(List.of(definition));
        RuntimeException error =
                failure.equals("disabled")
                        ? new IllegalStateException("BACKFILL_DISABLED")
                        : new IllegalArgumentException(
                                failure.equals("conflict")
                                        ? "GRAPH_IDEMPOTENCY_CONFLICT"
                                        : "Invalid date range");
        when(graph.prepareBackfill(any(), any(), any(), any())).thenThrow(error);
        assertThatThrownBy(
                        () ->
                                api.create(
                                        "operator",
                                        "key",
                                        new IndicatorBackfillController.Backfill(
                                                "SYNC_INDICATORS",
                                                List.of("BANK"),
                                                2,
                                                day,
                                                day,
                                                "PREFER_EXISTING",
                                                null,
                                                null,
                                                null)))
                .isInstanceOfSatisfying(
                        org.springframework.web.server.ResponseStatusException.class,
                        result ->
                                assertThat(result.getStatusCode().value())
                                        .isEqualTo(
                                                failure.equals("disabled")
                                                        ? 503
                                                        : failure.equals("conflict") ? 409 : 400));
        verify(graph, never()).requestId(any());
    }
}
