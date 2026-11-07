package com.omni.platform.modules.scheduler.dependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;

class JobDependencyContextFactoryTest {

    @Test
    void expandsEverySelectedSymbolIntoExactEnforcedEodDependency() {
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        SymbolKeyProjection hpg = symbol(" HPG ", " HOSE ");
        SymbolKeyProjection vnm = symbol("VNM", "HOSE");
        when(symbolRepository.findBySectorCodesAndLevel(null, 1)).thenReturn(List.of(hpg, vnm));
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_INDICATORS);
        job.setConfigJson(Map.of());

        JobExecutionContext context =
                new JobDependencyContextFactory(symbolRepository).create(job, "execution-1");

        assertThat(context.getDependsOnDatasets())
                .containsExactly(
                        Map.of(
                                "dataset",
                                "eod",
                                "partition",
                                Map.of(
                                        "exchange", "hose",
                                        "code", "hpg"),
                                "conditions",
                                List.of("EXISTS", "READY"),
                                "mode",
                                "ENFORCED"),
                        Map.of(
                                "dataset",
                                "eod",
                                "partition",
                                Map.of(
                                        "exchange", "hose",
                                        "code", "vnm"),
                                "conditions",
                                List.of("EXISTS", "READY"),
                                "mode",
                                "ENFORCED"));
    }

    @Test
    void symbolWorkUsesOnlyItsExactPartitionAndPreservesHyphensInCode() {
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_INDICATORS);
        job.setConfigJson(Map.of());

        JobExecutionContext context =
                new JobDependencyContextFactory(symbolRepository)
                        .create(job, "execution-symbol", "SYMBOL", " HOSE-ABC-DEF ");

        assertThat(context.getDependsOnDatasets())
                .containsExactly(
                        Map.of(
                                "dataset",
                                "eod",
                                "partition",
                                Map.of("exchange", "hose", "code", "abc-def"),
                                "conditions",
                                List.of("EXISTS", "READY"),
                                "mode",
                                "ENFORCED"));
        verify(symbolRepository, never()).findBySectorCodesAndLevel(any(), anyInt());
    }

    @Test
    void malformedSymbolWorkKeyFailsClosed() {
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_INDICATORS);
        job.setConfigJson(Map.of());
        JobDependencyContextFactory factory = new JobDependencyContextFactory(symbolRepository);

        assertThatThrownBy(() -> factory.create(job, "execution-symbol", "SYMBOL", "HOSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXCHANGE-CODE");
        assertThatThrownBy(() -> factory.create(job, "execution-symbol", "SYMBOL", "-HPG"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(job, "execution-symbol", "SYMBOL", "HOSE-"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void globalIndicatorWorkRetainsFullSelectedSymbolExpansion() {
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        var symbols = List.of(symbol("HPG", "HOSE"), symbol("VNM", "HOSE"));
        when(symbolRepository.findBySectorCodesAndLevel(null, 1)).thenReturn(symbols);
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_INDICATORS);
        job.setConfigJson(Map.of());

        JobExecutionContext context =
                new JobDependencyContextFactory(symbolRepository)
                        .create(job, "execution-global", "GLOBAL", "SYNC_INDICATORS:ANALYZER");

        assertThat(context.getDependsOnDatasets()).hasSize(2);
        verify(symbolRepository).findBySectorCodesAndLevel(null, 1);
    }

    @Test
    void leavesNonIndicatorJobsWithoutRuntimeExpansion() {
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setConfigJson(Map.of());

        JobExecutionContext context =
                new JobDependencyContextFactory(symbolRepository).create(job, "execution-2");

        assertThat(context.getDependsOnDatasets()).isEmpty();
        verify(symbolRepository, never()).findBySectorCodesAndLevel(any(), anyInt());
    }

    @Test
    void sectorWorkRequiresOnlyItsMembersAndRejectsAnUnresolvedSectorKey() {
        var repository = mock(SymbolRepository.class);
        var member = symbol("HPG", "HOSE");
        when(repository.findBySectorCodesAndLevel(any(), anyInt())).thenReturn(List.of(member));
        var factory = new JobDependencyContextFactory(repository);
        var job = new JobDefinition();
        job.setJobType(JobType.PRECOMPUTE_SECTOR_FEATURES);
        job.setConfigJson(Map.of("sectorLevel", 2, "timeframe", "1d"));
        var result = factory.create(job, "sector", "SECTOR", "BANK");
        assertThat(result.getDependsOnDatasets()).singleElement().satisfies(dependency -> {
            assertThat(dependency).containsEntry("dataset", "symbol-features");
            assertThat(dependency).containsEntry("partition", Map.of("exchange", "hose", "code", "hpg", "timeframe", "1d"));
            assertThat(dependency).containsEntry("mode", "ENFORCED");
        });
        assertThatThrownBy(() -> factory.create(job, "sector", "GLOBAL", "BANK")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(job, "sector", "SECTOR", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(job, "sector", "SECTOR", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backtestExpandsSelectedOrGlobalSectorPartitionsWithoutInferringReadiness() {
        var repository = mock(SymbolRepository.class);
        when(repository.findDistinctSectorCodesByLevel(any(), anyInt())).thenReturn(List.of("BANK"));
        var factory = new JobDependencyContextFactory(repository);
        var job = new JobDefinition();
        job.setJobType(JobType.SECTOR_ROTATION_BACKTEST);
        for (var codes : List.of(List.<String>of(), List.of("BANK"))) {
            job.setConfigJson(Map.of("sectorLevel", 1, "timeframe", "1d", "sectorCodes", codes));
            var result = factory.create(job, "backtest", "GLOBAL", "VN");
            assertThat(result.getDependsOnDatasets()).singleElement().satisfies(dependency -> {
                assertThat(dependency).containsEntry("dataset", "sector-features");
                assertThat(dependency).containsEntry("partition", Map.of("timeframe", "1d", "sector_level", "1", "sector_code", "bank"));
            });
        }
    }

    private SymbolKeyProjection symbol(String code, String exchange) {
        SymbolKeyProjection symbol = mock(SymbolKeyProjection.class);
        when(symbol.getCode()).thenReturn(code);
        when(symbol.getExchange()).thenReturn(exchange);
        return symbol;
    }
}
