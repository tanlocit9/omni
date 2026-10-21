package com.omni.platform.modules.scheduler.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

class JobRunWorkContextTest {
    @Test
    void enforcesBoundsUniqueWorkAndImmutableChildSnapshotWithoutDatabaseReads() {
        var parent = new JobExecutionHistory();
        parent.setId(UUID.randomUUID());
        parent.setJob(new JobDefinition());
        parent.setJobRunId(UUID.randomUUID());
        parent.setJobRunStepIndex(2);
        parent.setMetaJson(Map.of("businessDate", "2035-01-02"));
        var entities = mock(EntityManager.class);
        var json = JsonMapper.builder().build();
        var work = WorkIdentity.of(WorkType.SYMBOL, "HOSE-AAA");
        var context = new JobRunWorkContext(parent, entities, null, json, 2);
        var child = context.child(work, Map.of("symbolKey", "obsolete"), Instant.EPOCH);
        assertThat(child.getJobRunId()).isEqualTo(parent.getJobRunId());
        assertThat(child.getJobRunStepIndex()).isEqualTo(2);
        assertThat(child.getParentLogId()).isEqualTo(parent.getId());
        assertThat(child.getMetaJson())
                .containsEntry("workKey", "HOSE-AAA")
                .containsEntry("runPreparationVersion", "1")
                .doesNotContainKey("symbolKey");
        assertThatThrownBy(() -> context.child(work, Map.of(), Instant.EPOCH))
                .hasMessageContaining("DUPLICATE_WORK");
        assertThatThrownBy(() -> context.children().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        var bounded = new JobRunWorkContext(parent, entities, null, json, 0);
        assertThatThrownBy(() -> bounded.child(work, Map.of(), Instant.EPOCH))
                .hasMessageContaining("WORK_BOUND");
        assertThat(bounded.offsets(java.util.List.of())).isEmpty();
        assertThatThrownBy(() -> new JobRunWorkContext(parent, entities, null, json, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobRunWorkContext(parent, entities, null, json, 5001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preparationRequiresExplicitEnablementAndOnlySupportsCanonicalCurrentGlobalTargets() {
        var topology = new com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider();
        var disabled =
                new JobRunPreparationService(
                        null, null, null, topology, null, null, null, null, false);
        var enabled =
                new JobRunPreparationService(
                        null, null, null, topology, null, null, null, null, true);
        var target =
                com.omni.platform.modules.scheduler.constants.JobDefinitionConfig
                        .JOB_DEFINITION_SEEDS
                        .getFirst()
                        .toEntity();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.supports(target)).isFalse();
        assertThatThrownBy(
                        () ->
                                disabled.prepareScheduled(
                                        target,
                                        new com.omni.platform.modules.scheduler.repositories
                                                .SchedulerClaim(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                "owner",
                                                Instant.EPOCH,
                                                Instant.MAX,
                                                Instant.EPOCH),
                                        Instant.EPOCH))
                .hasMessageContaining("DISABLED");
        assertThat(enabled.isEnabled()).isTrue();
        assertThat(enabled.supportsManual(target, Map.of())).isTrue();
        target.setCronExpr("custom");
        assertThat(enabled.supports(target)).isFalse();
        target.setCronExpr(null);
        assertThat(enabled.supports(target)).isFalse();
        target.setCronExpr(" ");
        assertThat(enabled.supports(target)).isFalse();
        var intraday =
                com
                        .omni
                        .platform
                        .modules
                        .scheduler
                        .constants
                        .JobDefinitionConfig
                        .JOB_DEFINITION_SEEDS
                        .stream()
                        .filter(s -> s.jobType() == JobDefinition.JobType.SYNC_INTRADAY_EOD)
                        .findFirst()
                        .orElseThrow()
                        .toEntity();
        assertThat(enabled.supportsManual(intraday, Map.of("tradingDate", "2035-01-01"))).isFalse();
        assertThat(enabled.supportsManual(intraday, Map.of())).isTrue();
    }
}
