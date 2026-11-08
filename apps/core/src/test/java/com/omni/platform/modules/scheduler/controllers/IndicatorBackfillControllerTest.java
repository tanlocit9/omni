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
    private final GraphWorkRequestService graph = mock(GraphWorkRequestService.class);
    private final JobDefinitionRepository definitions = mock(JobDefinitionRepository.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final IndicatorBackfillController api = new IndicatorBackfillController(graph, definitions, json);
    private final LocalDate day = LocalDate.of(2026, 9, 1);

    @ParameterizedTest
    @ValueSource(strings = {"default", "reuse", "rerun", "force", "force-rerun"})
    void acceptsOnlyTheIndicatorsGoalWithNormalizedFrozenScope(String policy) {
        Boolean requestedForce = policy.equals("default") ? null : policy.startsWith("force");
        Boolean requestedRerun = policy.equals("default") ? null : policy.equals("rerun") || policy.equals("force-rerun");
        boolean force = Boolean.TRUE.equals(requestedForce);
        boolean rerun = force || Boolean.TRUE.equals(requestedRerun);
        var definition = new JobDefinition();
        definition.setId(UUID.randomUUID()); definition.setJobType(JobType.SYNC_INDICATORS); definition.setIsActive(true);
        var wrongType = new JobDefinition(); wrongType.setJobType(JobType.SYNC_SYMBOLS);
        var inactive = new JobDefinition(); inactive.setJobType(JobType.SYNC_INDICATORS); inactive.setIsActive(false);
        var alias = new JobDefinition(); alias.setJobType(JobType.SYNC_INDICATORS); alias.setIsActive(true); alias.setCanonicalDefinitionId(definition.getId());
        when(definitions.findAll()).thenReturn(List.of(definition, wrongType, inactive, alias));
        UUID execution = UUID.randomUUID(), request = UUID.randomUUID();
        when(graph.prepareBackfill(any(), eq(definition.getId()), any(), any())).thenReturn(execution);
        when(graph.requestId(execution)).thenReturn(request);
        var result = api.create("operator", "backfill-1", new IndicatorBackfillController.Backfill(
                "SYNC_INDICATORS", List.of("bank", "BANK"), 2, day, day, requestedForce, requestedRerun));
        assertThat(result).containsEntry("executionId", execution).containsEntry("requestId", request);
        assertThat(result).containsEntry("force", force).containsEntry("rerun", rerun);
        var captured = ArgumentCaptor.forClass(JobRunSnapshot.Request.class);
        verify(graph).prepareBackfill(captured.capture(), eq(definition.getId()), any(), eq(Map.of("force", force, "rerun", rerun)));
        assertThat(captured.getValue().mode()).isEqualTo(JobRunSnapshot.Mode.BACKFILL);
        var payload = json.readTree(captured.getValue().parametersJson());
        assertThat(payload.get("sectorCodes").size()).isEqualTo(1);
        assertThat(payload.get("force").asBoolean()).isEqualTo(force);
        assertThat(payload.get("rerun").asBoolean()).isEqualTo(rerun);
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal", "date", "end", "level", "highLevel", "noLevel", "sectors", "noSectors", "manySectors", "code", "nullCode", "null"})
    void rejectsUnboundedOrInvalidScopeBeforeCreatingWork(String invalid) {
        var input = new IndicatorBackfillController.Backfill(invalid.equals("goal") ? "SYNC_SIGNALS" : "SYNC_INDICATORS",
                invalid.equals("noSectors") ? null : invalid.equals("manySectors") ? java.util.Collections.nCopies(101, "BANK")
                        : invalid.equals("sectors") ? List.of() : java.util.Collections.singletonList(invalid.equals("nullCode") ? null : invalid.equals("code") ? "../x" : "BANK"),
                invalid.equals("noLevel") ? null : Integer.valueOf(invalid.equals("level") ? 0 : invalid.equals("highLevel") ? 5 : 2),
                invalid.equals("date") ? null : day, invalid.equals("end") ? null : day, false, null);
        assertThatThrownBy(() -> api.create("operator", "key", invalid.equals("null") ? null : input))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(graph, definitions);
    }

    @Test
    void refusesAmbiguousOrAbsentCanonicalTarget() {
        when(definitions.findAll()).thenReturn(List.of());
        assertThatThrownBy(() -> api.create("operator", "key", new IndicatorBackfillController.Backfill(
                "SYNC_INDICATORS", List.of("BANK"), 2, day, day, false, null)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(graph);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "invalid", "conflict"})
    void translatesAdmissionFailuresWithoutReportingAccepted(String failure) {
        var definition = new JobDefinition();
        definition.setId(UUID.randomUUID()); definition.setJobType(JobType.SYNC_INDICATORS); definition.setIsActive(true);
        when(definitions.findAll()).thenReturn(List.of(definition));
        RuntimeException error = failure.equals("disabled") ? new IllegalStateException("BACKFILL_DISABLED")
                : new IllegalArgumentException(failure.equals("conflict") ? "GRAPH_IDEMPOTENCY_CONFLICT" : "Invalid date range");
        when(graph.prepareBackfill(any(), any(), any(), any())).thenThrow(error);
        assertThatThrownBy(() -> api.create("operator", "key", new IndicatorBackfillController.Backfill(
                "SYNC_INDICATORS", List.of("BANK"), 2, day, day, null, null)))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        result -> assertThat(result.getStatusCode().value()).isEqualTo(
                                failure.equals("disabled") ? 503 : failure.equals("conflict") ? 409 : 400));
        verify(graph, never()).requestId(any());
    }
}
