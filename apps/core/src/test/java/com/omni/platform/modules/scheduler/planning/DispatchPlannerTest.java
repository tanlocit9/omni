package com.omni.platform.modules.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Reason;
import com.omni.platform.modules.scheduler.planning.DispatchPlan.Status;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.StaticJobTopology;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.DefinitionMapping;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.Edge;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

class DispatchPlannerTest {

    private static final Instant SNAPSHOT_AT = Instant.parse("2026-10-11T00:00:00Z");

    private final StaticJobTopologyProvider topologyProvider = new StaticJobTopologyProvider();
    private final DispatchPlanner planner = new DispatchPlanner(topologyProvider);

    @Test
    void rejectsIncompleteSnapshotsWithoutTreatingMissingGroupsAsZero() {
        BacklogSnapshot snapshot = snapshot(false, Map.of(scope(JobType.SYNC_SYMBOLS), counts(1, 1, 0, 0)));

        DispatchPlan plan = plan(snapshot, 10, null);

        assertThat(plan.status()).isEqualTo(Status.INCOMPLETE_SNAPSHOT);
        assertThat(plan.selections()).isEmpty();
    }

    @Test
    void selectsFirstDispatchableNodeWhileIgnoringNonDispatchableUpstreamOccupancy() {
        DispatchScope root = scope(JobType.SYNC_SYMBOLS);
        DispatchScope descendant = scope(JobType.SYNC_STOCK_PRICE);
        BacklogSnapshot snapshot = snapshot(true, Map.of(
                root, counts(2, 0, 1, 1),
                descendant, counts(3, 3, 0, 0)));

        DispatchPlan plan = plan(snapshot, 2, TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS));

        assertThat(plan.status()).isEqualTo(Status.PLANNED);
        assertThat(plan.selections()).singleElement().satisfies(selection -> {
            assertThat(selection.scope()).isEqualTo(descendant);
            assertThat(selection.quota()).isEqualTo(2);
        });
    }

    @Test
    void rotatesIndependentRootsAndPreventsSmallBudgetStarvation() {
        DispatchScope symbols = scope(JobType.SYNC_SYMBOLS);
        DispatchScope metadata = scope(JobType.SYNC_METADATA);
        BacklogSnapshot snapshot = snapshot(true, Map.of(
                symbols, counts(4, 4, 0, 0),
                metadata, counts(4, 4, 0, 0)));

        DispatchPlan first = plan(snapshot, 1, TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS));
        DispatchPlan second = plan(snapshot, 1, first.nextRootCursor());

        assertThat(first.selections()).singleElement()
                .extracting(selection -> selection.scope().node())
                .isEqualTo(TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS));
        assertThat(second.selections()).singleElement()
                .extracting(selection -> selection.scope().node())
                .isEqualTo(TopologyNodeKey.forJobType(JobType.SYNC_METADATA));
    }

    @Test
    void allocatesRoundRobinAndReassignsUnusedQuotaDeterministically() {
        DispatchScope symbols = scope(JobType.SYNC_SYMBOLS);
        DispatchScope metadata = scope(JobType.SYNC_METADATA);
        BacklogSnapshot snapshot = snapshot(true, Map.of(
                symbols, counts(1, 1, 0, 0),
                metadata, counts(5, 5, 0, 0)));

        DispatchPlan plan = plan(snapshot, 4, TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS));

        assertThat(plan.selections()).hasSize(2);
        assertThat(plan.selections().get(0).scope()).isEqualTo(symbols);
        assertThat(plan.selections().get(0).quota()).isEqualTo(1);
        assertThat(plan.selections().get(1).scope()).isEqualTo(metadata);
        assertThat(plan.selections().get(1).quota()).isEqualTo(3);
        assertThat(plan.selections()).extracting(DispatchPlan.Selection::order).containsExactly(0, 1);
    }

    @Test
    void returnsNoWorkAndRejectsUnknownRootCursor() {
        BacklogSnapshot snapshot = snapshot(true, Map.of(
                scope(JobType.SYNC_SYMBOLS), counts(2, 0, 2, 0)));

        assertThat(plan(snapshot, 3, null).status()).isEqualTo(Status.NO_DISPATCHABLE_WORK);
        assertThatThrownBy(() -> plan(snapshot, 3, new TopologyNodeKey("not-a-root")))
                .hasMessageContaining("Unknown root cursor");
    }

    @Test
    void preservesDistinctScopesForDefinitionsSharingOneNode() {
        List<JobDefinitionSeed> stockSeeds = JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream()
                .filter(seed -> seed.jobType() == JobType.SYNC_STOCK_PRICE)
                .limit(2)
                .toList();
        DispatchScope first = scope(stockSeeds.get(0));
        DispatchScope second = scope(stockSeeds.get(1));
        BacklogSnapshot snapshot = snapshot(true, Map.of(
                first, counts(1, 1, 0, 0),
                second, counts(1, 1, 0, 0)));

        DispatchPlan plan = plan(snapshot, 2, TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS));

        assertThat(plan.selections()).extracting(DispatchPlan.Selection::scope)
                .containsExactly(first, second);
    }

    @Test
    void reconcilesSharedDescendantOnceAcrossIndependentRoots() {
        TopologyNodeKey rootA = new TopologyNodeKey("root-a");
        TopologyNodeKey rootB = new TopologyNodeKey("root-b");
        TopologyNodeKey shared = new TopologyNodeKey("shared");
        JobDefinitionIdentity rootAIdentity = identity(JobType.SYNC_SYMBOLS, "0 0 1 * * *");
        JobDefinitionIdentity rootBIdentity = identity(JobType.SYNC_METADATA, "0 0 2 * * *");
        JobDefinitionIdentity sharedIdentity = identity(JobType.SYNC_SIGNALS, "0 0 3 * * *");
        StaticJobTopology topology = StaticJobTopology.create(new StaticTopologyDeclaration(
                List.of(rootA, rootB, shared),
                List.of(new Edge(rootA, shared), new Edge(rootB, shared)),
                List.of(
                        new DefinitionMapping(rootAIdentity, rootA),
                        new DefinitionMapping(rootBIdentity, rootB),
                        new DefinitionMapping(sharedIdentity, shared))));
        DispatchScope scope = new DispatchScope(shared, UUID.randomUUID(), sharedIdentity);
        DispatchPlanner joinPlanner = new DispatchPlanner(topology);

        DispatchPlan plan = joinPlanner.plan(
                snapshot(true, Map.of(scope, counts(4, 4, 0, 0))), 4, rootA, SNAPSHOT_AT, Duration.ofSeconds(30));

        assertThat(plan.selections()).singleElement().satisfies(selection -> {
            assertThat(selection.scope()).isEqualTo(scope);
            assertThat(selection.quota()).isEqualTo(4);
            assertThat(selection.reason()).isEqualTo(Reason.SHARED_DESCENDANT);
        });
    }

    @Test
    void rejectsOldAndFutureSnapshotsWithoutAdvancingCursor() {
        BacklogSnapshot snapshot = snapshot(true, Map.of(scope(JobType.SYNC_SYMBOLS), counts(1, 1, 0, 0)));
        TopologyNodeKey cursor = TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS);
        for (Instant evaluatedAt : List.of(SNAPSHOT_AT.plusSeconds(31), SNAPSHOT_AT.minusNanos(1))) {
            DispatchPlan result = planner.plan(snapshot, 1, cursor, evaluatedAt, Duration.ofSeconds(30));
            assertThat(result.status()).isEqualTo(Status.STALE_SNAPSHOT);
            assertThat(result.selections()).isEmpty();
            assertThat(result.nextRootCursor()).isEqualTo(cursor);
        }
        assertThat(planner.plan(snapshot, 1, cursor, SNAPSHOT_AT.plusSeconds(30), Duration.ofSeconds(30))
                .status()).isEqualTo(Status.PLANNED);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> planner.plan(snapshot, 1, cursor, SNAPSHOT_AT, invalid))
                    .hasMessageContaining("maxSnapshotAge must be positive");
        }
        assertThatThrownBy(() -> planner.plan(snapshot, 1, cursor, null, Duration.ofSeconds(30)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> planner.plan(snapshot, 1, cursor, SNAPSHOT_AT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectsFirstWorkOnEveryBranchAndDeduplicatesConvergingPaths() {
        TopologyNodeKey root = new TopologyNodeKey("root");
        TopologyNodeKey left = new TopologyNodeKey("left");
        TopologyNodeKey right = new TopologyNodeKey("right");
        TopologyNodeKey join = new TopologyNodeKey("join");
        JobDefinitionIdentity rootId = identity(JobType.SYNC_SYMBOLS, "0 0 1 * * *");
        JobDefinitionIdentity leftId = identity(JobType.SYNC_STOCK_PRICE, "0 0 2 * * *");
        JobDefinitionIdentity rightId = identity(JobType.SYNC_INDICATORS, "0 0 3 * * *");
        JobDefinitionIdentity joinId = identity(JobType.SYNC_SIGNALS, "0 0 4 * * *");
        StaticJobTopology topology = StaticJobTopology.create(new StaticTopologyDeclaration(
                List.of(root, left, right, join),
                List.of(new Edge(root, left), new Edge(root, right),
                        new Edge(left, join), new Edge(right, join)),
                List.of(new DefinitionMapping(rootId, root), new DefinitionMapping(leftId, left),
                        new DefinitionMapping(rightId, right), new DefinitionMapping(joinId, join))));
        DispatchPlanner branchPlanner = new DispatchPlanner(topology);
        DispatchScope leftScope = new DispatchScope(left, UUID.randomUUID(), leftId);
        DispatchScope rightScope = new DispatchScope(right, UUID.randomUUID(), rightId);
        DispatchScope joinScope = new DispatchScope(join, UUID.randomUUID(), joinId);
        DispatchPlan both = branchPlanner.plan(snapshot(true, Map.of(
                leftScope, counts(1, 1, 0, 0), rightScope, counts(1, 1, 0, 0),
                joinScope, counts(2, 2, 0, 0))), 4, root, SNAPSHOT_AT, Duration.ofSeconds(30));
        assertThat(both.selections()).extracting(DispatchPlan.Selection::scope)
                .containsExactly(leftScope, rightScope);
        DispatchPlan bypass = branchPlanner.plan(snapshot(true, Map.of(
                leftScope, counts(1, 1, 0, 0), rightScope, counts(1, 0, 1, 0),
                joinScope, counts(1, 1, 0, 0))), 2, root, SNAPSHOT_AT, Duration.ofSeconds(30));
        assertThat(bypass.selections()).extracting(DispatchPlan.Selection::scope)
                .containsExactly(leftScope, joinScope);
        DispatchPlan converged = branchPlanner.plan(snapshot(true, Map.of(
                joinScope, counts(2, 2, 0, 0))), 2, root, SNAPSHOT_AT, Duration.ofSeconds(30));
        assertThat(converged.selections()).singleElement()
                .satisfies(selection -> assertThat(selection.quota()).isEqualTo(2));
    }

    private DispatchPlan plan(BacklogSnapshot snapshot, int budget, TopologyNodeKey cursor) {
        return planner.plan(snapshot, budget, cursor, SNAPSHOT_AT, Duration.ofSeconds(30));
    }

    private JobDefinitionIdentity identity(JobType type, String cron) {
        return new JobDefinitionIdentity(
                com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource.ANALYZER,
                type,
                cron);
    }

    private BacklogSnapshot snapshot(boolean complete, Map<DispatchScope, BacklogCounts> entries) {
        return new BacklogSnapshot(SNAPSHOT_AT, complete, 100, new LinkedHashMap<>(entries));
    }

    private BacklogCounts counts(long pending, long dispatchable, long delayed, long inFlight) {
        return new BacklogCounts(pending, dispatchable, delayed, inFlight);
    }

    private DispatchScope scope(JobType jobType) {
        JobDefinitionSeed seed = JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream()
                .filter(candidate -> candidate.jobType() == jobType)
                .findFirst()
                .orElseThrow();
        return scope(seed);
    }

    private DispatchScope scope(JobDefinitionSeed seed) {
        return new DispatchScope(
                TopologyNodeKey.forJobType(seed.jobType()),
                UUID.nameUUIDFromBytes((seed.source() + seed.jobType().name() + seed.cronExpr()).getBytes()),
                JobDefinitionIdentity.from(seed));
    }
}
