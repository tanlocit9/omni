package com.omni.platform.modules.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Reason;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Selection;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Status;
import com.omni.platform.modules.scheduler.repositories.SchedulerBacklogGroup;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

class PlanningContractsTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final TopologyNodeKey NODE = new TopologyNodeKey("node");
    private static final DispatchScope SCOPE = new DispatchScope(
            NODE,
            UUID.randomUUID(),
            new JobDefinitionIdentity(DataSource.ANALYZER, JobType.SYNC_SIGNALS, "0 0 0 * * *"));

    @Test
    void backlogCountsRejectNegativeAndInvalidPartitionsAndExposeOccupancy() {
        assertThatThrownBy(() -> new BacklogCounts(-1, 0, 0, 0))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new BacklogCounts(1, 0, -1, 2))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new BacklogCounts(2, 1, 0, 0))
                .hasMessageContaining("Pending count");

        assertThat(new BacklogCounts(0, 0, 0, 0).occupied()).isFalse();
        assertThat(new BacklogCounts(1, 0, 1, 0).occupied()).isTrue();
        assertThat(new BacklogCounts(1, 1, 0, 0).hasDispatchableWork()).isTrue();
    }

    @Test
    void snapshotRejectsInvalidBoundsNullEntriesAndOverflow() {
        assertThatThrownBy(() -> new BacklogSnapshot(NOW, true, 0, Map.of()))
                .hasMessageContaining("groupLimit");
        assertThatThrownBy(() -> new BacklogSnapshot(NOW, true, 1, Map.of(
                SCOPE, new BacklogCounts(1, 1, 0, 0),
                anotherScope(), new BacklogCounts(1, 1, 0, 0))))
                .hasMessageContaining("exceed");

        Map<DispatchScope, BacklogCounts> nullScope = new LinkedHashMap<>();
        nullScope.put(null, new BacklogCounts(1, 1, 0, 0));
        assertThatThrownBy(() -> new BacklogSnapshot(NOW, true, 1, nullScope))
                .isInstanceOf(NullPointerException.class);

        Map<DispatchScope, BacklogCounts> nullCounts = new LinkedHashMap<>();
        nullCounts.put(SCOPE, null);
        assertThatThrownBy(() -> new BacklogSnapshot(NOW, true, 1, nullCounts))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dispatchPlanAndSelectionEnforceBudgetStatusQuotaAndOrder() {
        Selection selection = new Selection(NODE, SCOPE, 1,
                Reason.FIRST_DISPATCHABLE_ON_ROOT_PATH, 0);
        assertThatThrownBy(() -> new DispatchPlan(NOW, Status.PLANNED, 0, NODE, List.of()))
                .hasMessageContaining("dispatchBudget");
        assertThatThrownBy(() -> new DispatchPlan(NOW, Status.PLANNED, 1, NODE,
                List.of(selection, selection)))
                .hasMessageContaining("exceeds");
        assertThatThrownBy(() -> new DispatchPlan(NOW, Status.NO_DISPATCHABLE_WORK, 1, NODE,
                List.of(selection)))
                .hasMessageContaining("Non-planned");
        assertThatThrownBy(() -> new Selection(NODE, SCOPE, 0,
                Reason.FIRST_DISPATCHABLE_ON_ROOT_PATH, 0))
                .hasMessageContaining("quota");
        assertThatThrownBy(() -> new Selection(NODE, SCOPE, 1,
                Reason.FIRST_DISPATCHABLE_ON_ROOT_PATH, -1))
                .hasMessageContaining("order");
    }

    @Test
    void rawBacklogGroupRejectsInvalidIdentityAndNegativeCounts() {
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                null, DataSource.ANALYZER, JobType.SYNC_SIGNALS,
                "0 0 0 * * *", 1, 1, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                UUID.randomUUID(), null, JobType.SYNC_SIGNALS,
                "0 0 0 * * *", 1, 1, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                UUID.randomUUID(), DataSource.ANALYZER, null,
                "0 0 0 * * *", 1, 1, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                UUID.randomUUID(), DataSource.ANALYZER, JobType.SYNC_SIGNALS,
                null, 1, 1, 0, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                UUID.randomUUID(), DataSource.ANALYZER, JobType.SYNC_SIGNALS,
                "0 0 0 * * *", 1, -1, 1, 0))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                UUID.randomUUID(), DataSource.ANALYZER, JobType.SYNC_SIGNALS,
                "0 0 0 * * *", 1, 0, -1, 2))
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new SchedulerBacklogGroup(
                UUID.randomUUID(), DataSource.ANALYZER, JobType.SYNC_SIGNALS,
                "0 0 0 * * *", 1, 1, 0, -1))
                .hasMessageContaining("negative");
    }

    private DispatchScope anotherScope() {
        return new DispatchScope(
                NODE,
                UUID.randomUUID(),
                new JobDefinitionIdentity(DataSource.ANALYZER, JobType.SYNC_SIGNALS, "0 1 0 * * *"));
    }
}
