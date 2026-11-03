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
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import com.omni.platform.shared.executions.WorkType;
import tools.jackson.databind.json.JsonMapper;

class OutputWorkScopeCompletionTest {
    @Test
    void outcomesCoverChangedOriginRowsWithinTheWorkExchangeAndEvaluationDate() {
        var jdbc=mock(JdbcTemplate.class);
        var gate=new OutputCompletionGate(jdbc,JsonMapper.builder().build());
        var execution=UUID.randomUUID();
        var attempt=UUID.randomUUID();
        var day=LocalDate.of(2026,11,3);
        var work=WorkExpansion.identity(JobType.EVALUATE_SIGNALS,WorkType.EXCHANGE,"HOSE",day,Map.of());
        when(jdbc.queryForList(anyString(),eq(execution))).thenReturn(List.of(Map.of("atomic_attempt_id",attempt)));
        when(jdbc.queryForObject(anyString(),eq(Boolean.class),any(),any(),eq(execution))).thenReturn(true);
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(execution))).thenReturn("[]");
        for (String kind : List.of("valid","exchange","future","dataset")) {
            String dataset=kind.equals("dataset")?"eod":"signals";
            var partition=dataset.equals("signals")?Map.of("exchange","hose","strategy","trend","timeframe","1d")
                    :Map.of("exchange","hose","code","hpg");
            String origin=kind.equals("future")?"2026-11-04":"2026-11-02";
            Map<String,String> key=dataset.equals("eod")?Map.of("date",origin):Map.of("symbol_key",kind.equals("exchange")?"HNX-HPG":"HOSE-HPG","signal_date",origin);
            var output=new OutputRegistrationEvent.Output(dataset,partition,"sha256:"+"a".repeat(64),List.of(LocalDate.parse(origin)),1,128,1,
                    "sha256:"+"b".repeat(64),List.of(new OutputRegistrationEvent.Column("signal_date","DATE",false)),List.of(),
                    new OutputRegistrationEvent.WorkScope(work,List.of(key)));
            var registration=new OutputRegistrationEvent.Registration(UUID.randomUUID(),execution,attempt,work,JobType.EVALUATE_SIGNALS,
                    WorkType.EXCHANGE,"HOSE",day,Map.of(),List.of(output.identity()),List.of(output),null);
            if (kind.equals("valid")) {
                assertThatCode(() -> gate.validate(registration)).doesNotThrowAnyException();
                when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(7L);
                var baseline=new OutputBackfillService.Baseline(7L,UUID.randomUUID(),output);
                assertThatThrownBy(() -> new OutputBackfillService(jdbc,JsonMapper.builder().build()).commit(baseline))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot invent work readiness");
            }
            else assertThatThrownBy(() -> gate.validate(registration)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rowPromotionAdvancesPagingRevisionEvenWhenLatestPartitionPointerDoesNotChange() {
        var jdbc=mock(JdbcTemplate.class);
        var execution=UUID.randomUUID();
        when(jdbc.queryForList(contains("FROM output_registration_outputs"),eq(execution))).thenReturn(List.of(Map.of(
                "atomic_work_id","work:"+"a".repeat(64),"dataset","eod","partition","{\"exchange\":\"hose\",\"code\":\"hpg\"}",
                "registration_id",UUID.randomUUID(),"output_index",0,"generation",1L)));
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call ->
                call.<String>getArgument(0).startsWith("INSERT INTO output_ready_rows")?1:0);
        new OutputReadinessStore(jdbc,true,true).promote(execution);
        verify(jdbc).update("UPDATE output_ready_revision SET revision=revision+1 WHERE id=1");
    }
}
