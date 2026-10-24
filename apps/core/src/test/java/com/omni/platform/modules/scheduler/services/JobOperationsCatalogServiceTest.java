package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;

@ExtendWith(MockitoExtension.class)
class JobOperationsCatalogServiceTest {
    @Mock private JobDefinitionRepository definitions;
    @Mock private JobExecutionHistoryRepository executions;
    private JobOperationsCatalogService service;

    @BeforeEach
    void setUp() {
        service = new JobOperationsCatalogService(definitions, executions);
    }

    @Test
    void listFiltersAndBoundsPaginationDeterministically() {
        JobDefinition indicator = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, true);
        JobDefinition symbols = job(JobType.SYNC_SYMBOLS, DataSource.VCI, false);
        when(definitions.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(indicator, symbols));
        when(executions.findFirstByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(
                        indicator.getId()))
                .thenReturn(Optional.empty());

        var page = service.list("indicator", "sync_indicators", true, -3, 500);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.id()).isEqualTo(indicator.getId());
                            assertThat(item.triggerable()).isFalse();
                            assertThat(item.triggerBlockReason())
                                    .isEqualTo("Manual job triggering is deferred");
                            assertThat(item.workKey()).isEqualTo("SYNC_INDICATORS:ANALYZER");
                        });
        assertThat(service.list(null, null, null, 0, 25).items()).hasSize(2);
        assertThat(service.list(" ", "", false, 0, 25).items())
                .extracting(item -> item.id())
                .containsExactly(symbols.getId());
        assertThat(service.list(null, "SYNC_METADATA", null, 0, 25).items()).isEmpty();
        assertThat(service.list("missing", null, null, 0, 25).items()).isEmpty();
        assertThat(service.list(null, null, null, 5, 0).items()).isEmpty();
    }

    @Test
    void detailOnlyReturnsLogicalDependencyNamesAndNoRawConfiguration() {
        JobDefinition indicator = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, true);
        indicator.setConfigJson(
                Map.of(
                        "providerSecret", "never-return-this",
                        "physicalPath", "s3://private-bucket/eod",
                        "dependsOnJobs", List.of("SYNC_STOCK_PRICE"),
                        "dependsOnDatasets",
                                List.of(Map.of("dataset", "eod", "bucket", "private-bucket")),
                        "producesDatasets", List.of(Map.of("dataset", "indicators"))));
        when(definitions.findById(indicator.getId())).thenReturn(Optional.of(indicator));
        when(executions.findTop20ByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(
                        indicator.getId()))
                .thenReturn(List.of());

        var result = service.detail(indicator.getId());

        assertThat(result.dependencies().jobs()).containsExactly("SYNC_STOCK_PRICE");
        assertThat(result.dependencies().datasets()).containsExactly("eod");
        assertThat(result.dependencies().produces()).containsExactly("indicators");
        assertThat(result.toString())
                .doesNotContain("never-return-this", "private-bucket", "s3://");
        var history = new com.omni.platform.modules.scheduler.entities.JobExecutionHistory();
        history.setId(UUID.randomUUID());
        history.setStatus(
                com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus.SUCCESS);
        when(executions.findTop20ByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(
                        indicator.getId()))
                .thenReturn(List.of(history));
        assertThat(service.detail(indicator.getId()).lastExecution().id())
                .isEqualTo(history.getId());
    }

    @Test
    void executionLookupPreservesReadOnlyHistoryAndSanitization() {
        var execution = new com.omni.platform.modules.scheduler.entities.JobExecutionHistory();
        execution.setId(UUID.randomUUID());
        execution.setStatus(
                com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus.SUCCESS);
        execution.setError("token=unsafe s3://private/path");
        when(executions.findById(execution.getId())).thenReturn(Optional.of(execution));
        assertThat(service.executionStatus(execution.getId()).error())
                .isEqualTo("token=[redacted] [redacted-location]");
        UUID missing = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executionStatus(missing))
                .isInstanceOfSatisfying(
                        JobOperationException.class,
                        error -> {
                            assertThat(error.getStatus())
                                    .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
                            assertThat(error.getCode()).isEqualTo("job_execution_not_found");
                        });
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.detail(missing))
                .isInstanceOf(JobOperationException.class);
    }

    @Test
    void catalogHandlesLegacyDependencyShapesWithoutExposingConfiguration() {
        var definition = job(JobType.SYNC_METADATA, DataSource.ANALYZER, true);
        when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(executions.findTop20ByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(
                        definition.getId()))
                .thenReturn(List.of());
        definition.setConfigJson(null);
        assertThat(service.detail(definition.getId()).dependencies().jobs()).isEmpty();
        var mixed = new java.util.ArrayList<Object>();
        mixed.add(Map.of("dataset", "eod"));
        mixed.add(Map.of("ignored", "private-value"));
        mixed.add("indicators");
        mixed.add(" ");
        mixed.add(42);
        mixed.add(null);
        mixed.add(Map.of("dataset", "eod"));
        definition.setConfigJson(
                Map.of(
                        "dependsOnJobs",
                        List.of(" SYNC_SYMBOLS ", " "),
                        "dependsOnDatasets",
                        mixed,
                        "producesDatasets",
                        "not-a-list"));
        var detail = service.detail(definition.getId());
        assertThat(detail.dependencies().jobs()).containsExactly("SYNC_SYMBOLS");
        assertThat(detail.dependencies().datasets()).containsExactly("eod", "indicators");
        assertThat(detail.dependencies().produces()).isEmpty();
        assertThat(detail.toString()).doesNotContain("private-value");
        assertThat(JobOperationsCatalogService.sanitize(null)).isNull();
        assertThat(JobOperationsCatalogService.sanitize("x".repeat(600))).hasSize(500);
    }

    private static JobDefinition job(JobType type, DataSource source, boolean active) {
        JobDefinition value = new JobDefinition();
        value.setId(UUID.randomUUID());
        value.setTitle(type.name().replace('_', ' '));
        value.setJobType(type);
        value.setSource(source);
        value.setIsActive(active);
        value.setCronExpr("0 0 * * * *");
        value.setConfigJson(Map.of());
        return value;
    }
}
