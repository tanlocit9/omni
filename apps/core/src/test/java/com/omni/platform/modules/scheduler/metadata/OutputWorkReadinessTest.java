package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.shared.executions.WorkType;
import tools.jackson.databind.json.JsonMapper;

class OutputWorkReadinessTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OutputWorkReadiness service =
            new OutputWorkReadiness(jdbc, JsonMapper.builder().build());
    private final UUID execution = UUID.randomUUID();
    private final DatasetRef ref = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg"));

    @Test
    void canonicalIdentitySeparatesDateSymbolAndParameters() {
        var day = LocalDate.of(2026, 11, 3);
        var id =
                WorkExpansion.identity(
                        JobType.SYNC_STOCK_PRICE, WorkType.SYMBOL, "HOSE-HPG", day, Map.of());
        assertThat(id)
                .isNotEqualTo(
                        WorkExpansion.identity(
                                JobType.SYNC_STOCK_PRICE,
                                WorkType.SYMBOL,
                                "HOSE-HPG",
                                day.plusDays(1),
                                Map.of()));
        assertThat(id)
                .isNotEqualTo(
                        WorkExpansion.identity(
                                JobType.SYNC_STOCK_PRICE,
                                WorkType.SYMBOL,
                                "HOSE-FPT",
                                day,
                                Map.of()));
        assertThat(id)
                .isNotEqualTo(
                        WorkExpansion.identity(
                                JobType.SYNC_STOCK_PRICE,
                                WorkType.SYMBOL,
                                "HOSE-HPG",
                                day,
                                Map.of("source", "vnd")));
    }

    @Test
    void unenrolledWorkKeepsExplicitLegacyBinding() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(execution))).thenReturn(false);
        var versions = Map.of(ref, "v1");
        assertThat(service.bind(execution, versions)).isSameAs(versions);
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void missingEnrolledPredecessorDeclarationFailsClosedEvenWithNoRequestedRefs() {
        when(jdbc.queryForObject(
                        contains("EXISTS(SELECT 1 FROM output_completion_contracts"),
                        eq(Boolean.class),
                        eq(execution)))
                .thenReturn(true);
        when(jdbc.queryForObject(
                        contains("declaration.execution_id IS NULL"),
                        eq(Boolean.class),
                        eq(execution)))
                .thenReturn(true);
        assertThat(service.bind(execution, Map.of())).isNull();
        verify(jdbc, never()).queryForList(anyString(), eq(execution));
    }

    @Test
    void enrollmentCannotFallBackToPartitionWhenFrozenWorkIsMissingOrAmbiguous() {
        when(jdbc.queryForObject(
                        contains("EXISTS(SELECT 1 FROM output_completion_contracts"),
                        eq(Boolean.class),
                        eq(execution)))
                .thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(execution), anyString(), anyString()))
                .thenReturn(List.of());
        assertThat(service.bind(execution, Map.of(ref, "v1"))).isNull();
        var row =
                Map.<String, Object>of(
                        "atomic_work_id",
                        "work:" + "a".repeat(64),
                        "business_date",
                        LocalDate.of(2026, 11, 3),
                        "atomic_attempt_id",
                        UUID.randomUUID(),
                        "data_version",
                        "v1");
        when(jdbc.queryForList(anyString(), eq(execution), anyString(), anyString()))
                .thenReturn(List.of(row, row));
        assertThat(service.bind(execution, Map.of(ref, "v1"))).isNull();
    }

    @Test
    void approvedInputCarriesGraphWorkDateAttemptAndVersionTogether() {
        when(jdbc.queryForObject(
                        contains("EXISTS(SELECT 1 FROM output_completion_contracts"),
                        eq(Boolean.class),
                        eq(execution)))
                .thenReturn(true);
        var attempt = UUID.randomUUID();
        var work = "work:" + "a".repeat(64);
        when(jdbc.queryForList(anyString(), eq(execution), anyString(), anyString()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "atomic_work_id",
                                        work,
                                        "business_date",
                                        LocalDate.of(2026, 11, 3),
                                        "atomic_attempt_id",
                                        attempt,
                                        "data_version",
                                        "v1")));
        var result = service.bind(execution, Map.of(ref, "v1"));
        var bound = result.keySet().iterator().next();
        assertThat(bound.approvedInput(result.get(bound)))
                .containsEntry("atomicWorkId", work)
                .containsEntry("atomicAttemptId", attempt.toString())
                .containsEntry("businessDate", "2026-11-03")
                .containsEntry("dataVersion", "v1");
        assertThat(bound)
                .isNotEqualTo(ref)
                .isNotEqualTo(ref.bindWork(work, LocalDate.of(2026, 11, 4), attempt));
        assertThatThrownBy(() -> ref.bindWork("wrong", LocalDate.now(), attempt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read("wrong"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void workLookupExposesRecordedApprovalsAndFrozenGraphInsteadOfFileHistory() {
        var work = "work:" + "a".repeat(64);
        var attempt = UUID.randomUUID();
        var parent =
                Map.<String, Object>of(
                        "atomicWorkId",
                        "work:" + "b".repeat(64),
                        "atomicAttemptId",
                        UUID.randomUUID());
        when(jdbc.queryForList(contains("FROM output_work_ready_catalog"), eq(work)))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "atomic_attempt_id",
                                        attempt,
                                        "execution_id",
                                        execution,
                                        "business_date",
                                        LocalDate.of(2026, 11, 3),
                                        "data_version",
                                        "sha256:" + "c".repeat(64),
                                        "output_json",
                                        "{\"dataset\":\"eod\"}",
                                        "inputs",
                                        "[{\"atomicWorkId\":\"recorded-upstream\"}]")));
        when(jdbc.queryForList(contains("FROM atomic_work_dependencies"), eq(attempt)))
                .thenReturn(List.of(parent));
        var provider = mock(OutputReadinessProvider.class);
        var page = Map.<String, Object>of("revision", 7L, "items", List.of());
        when(provider.page(0, 1, 7L)).thenReturn(page);
        var controller = new OutputReadinessController(provider);
        assertThat(controller.page(0, 1, 7L)).isSameAs(page);
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "workReadiness", service);
        var response = controller.work(work, null);
        assertThat(response).containsEntry("atomicWorkId", work);
        var item = service.read(work).getFirst();
        assertThat(item)
                .containsEntry("upstreamWorks", List.of(parent))
                .containsEntry(
                        "approvedInputs", List.of(Map.of("atomicWorkId", "recorded-upstream")));
    }

    @Test
    void privateWorkRouteForwardsTheOptionalFrozenAttempt() {
        var readiness = mock(OutputWorkReadiness.class);
        var controller = new OutputReadinessController(mock(OutputReadinessProvider.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "workReadiness", readiness);
        var attempt = UUID.randomUUID();
        String work = "work:" + "a".repeat(64);
        when(readiness.read(work, attempt))
                .thenReturn(List.of(Map.of("atomicAttemptId", attempt.toString())));
        assertThat((List<?>) controller.work(work, attempt).get("items")).hasSize(1);
        verify(readiness).read(work, attempt);
    }
}
