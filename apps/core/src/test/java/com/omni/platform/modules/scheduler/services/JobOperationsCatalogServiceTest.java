package com.omni.platform.modules.scheduler.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.omni.platform.modules.scheduler.config.ManualTriggerProperties;
import com.omni.platform.modules.scheduler.entities.BlockedJob;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.BlockedJobRepository;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;

@ExtendWith(MockitoExtension.class)
class JobOperationsCatalogServiceTest {
    @Mock private JobDefinitionRepository definitions;
    @Mock private JobExecutionHistoryRepository executions;
    @Mock private BlockedJobRepository blockedJobs;
    private JobOperationsCatalogService service;

    @BeforeEach
    void setUp() {
        service = new JobOperationsCatalogService(definitions, executions, blockedJobs,
                new ManualTriggerProperties(List.of("SYNC_INDICATORS:ANALYZER")));
    }

    @Test
    void listFiltersAndBoundsPaginationDeterministically() {
        JobDefinition indicator = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, true);
        JobDefinition symbols = job(JobType.SYNC_SYMBOLS, DataSource.VCI, true);
        when(definitions.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(indicator, symbols));
        when(executions.findFirstByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(indicator.getId()))
                .thenReturn(Optional.empty());
        when(blockedJobs.findByJobNameAndResolvedFalse("SYNC_INDICATORS_ANALYZER"))
                .thenReturn(Optional.empty());

        var page = service.list("indicator", "sync_indicators", true, -3, 500);

        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(indicator.getId());
            assertThat(item.triggerable()).isTrue();
            assertThat(item.workKey()).isEqualTo("SYNC_INDICATORS:ANALYZER");
        });
    }

    @Test
    void detailOnlyReturnsLogicalDependencyNamesAndNoRawConfiguration() {
        JobDefinition indicator = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, true);
        indicator.setConfigJson(Map.of(
                "providerSecret", "never-return-this",
                "physicalPath", "s3://private-bucket/eod",
                "dependsOnJobs", List.of("SYNC_STOCK_PRICE"),
                "dependsOnDatasets", List.of(Map.of("dataset", "eod", "bucket", "private-bucket")),
                "producesDatasets", List.of(Map.of("dataset", "indicators"))));
        when(definitions.findById(indicator.getId())).thenReturn(Optional.of(indicator));
        when(executions.findTop20ByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(indicator.getId()))
                .thenReturn(List.of());
        when(blockedJobs.findByJobNameAndResolvedFalse("SYNC_INDICATORS_ANALYZER"))
                .thenReturn(Optional.empty());

        var result = service.detail(indicator.getId());

        assertThat(result.dependencies().jobs()).containsExactly("SYNC_STOCK_PRICE");
        assertThat(result.dependencies().datasets()).containsExactly("eod");
        assertThat(result.dependencies().produces()).containsExactly("indicators");
        assertThat(result.toString()).doesNotContain("never-return-this", "private-bucket", "s3://");
    }

    @Test
    void inactiveAllowListConcurrencyAndDependencyBlocksAreDistinct() {
        JobDefinition inactive = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, false);
        JobDefinition excluded = job(JobType.SYNC_SYMBOLS, DataSource.VCI, true);
        JobDefinition claimed = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, true);
        claimed.setClaimToken(UUID.randomUUID());
        claimed.setClaimUntil(Instant.now().plusSeconds(60));
        JobDefinition blocked = job(JobType.SYNC_INDICATORS, DataSource.ANALYZER, true);
        BlockedJob record = new BlockedJob();
        record.setBlockReason("missing file:///var/data/eod password=unsafe");
        when(definitions.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(inactive, excluded, claimed, blocked));
        when(executions.findFirstByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(any()))
                .thenReturn(Optional.empty());
        when(blockedJobs.findByJobNameAndResolvedFalse("SYNC_INDICATORS_ANALYZER"))
                .thenReturn(Optional.of(record));

        var items = service.list(null, null, null, 0, 25).items();

        assertThat(items).extracting(item -> item.triggerBlockReason())
                .contains("Job definition is inactive",
                        "Job definition is not in the manual-trigger allow-list",
                        "Another scheduler or operator execution owns this job",
                        "missing [redacted-location] password=[redacted]");
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
