package com.omni.platform.modules.scheduler.dependencies;

import static org.assertj.core.api.Assertions.assertThat;
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
        when(symbolRepository.findBySectorCodesAndLevel(null, 1))
                .thenReturn(List.of(hpg, vnm));
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_INDICATORS);
        job.setConfigJson(Map.of());

        JobExecutionContext context =
                new JobDependencyContextFactory(symbolRepository)
                        .create(job, "execution-1");

        assertThat(context.getDependsOnDatasets()).containsExactly(
                Map.of(
                        "dataset", "eod",
                        "partition", Map.of(
                                "exchange", "hose",
                                "code", "hpg"),
                        "conditions", List.of("EXISTS", "READY"),
                        "mode", "ENFORCED"),
                Map.of(
                        "dataset", "eod",
                        "partition", Map.of(
                                "exchange", "hose",
                                "code", "vnm"),
                        "conditions", List.of("EXISTS", "READY"),
                        "mode", "ENFORCED"));
    }

    @Test
    void leavesNonIndicatorJobsWithoutRuntimeExpansion() {
        SymbolRepository symbolRepository = mock(SymbolRepository.class);
        JobDefinition job = new JobDefinition();
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setConfigJson(Map.of());

        JobExecutionContext context =
                new JobDependencyContextFactory(symbolRepository)
                        .create(job, "execution-2");

        assertThat(context.getDependsOnDatasets()).isEmpty();
        verify(symbolRepository, never())
                .findBySectorCodesAndLevel(any(), anyInt());
    }

    private SymbolKeyProjection symbol(String code, String exchange) {
        SymbolKeyProjection symbol = mock(SymbolKeyProjection.class);
        when(symbol.getCode()).thenReturn(code);
        when(symbol.getExchange()).thenReturn(exchange);
        return symbol;
    }
}
