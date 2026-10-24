package com.omni.platform.modules.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.planning.JobRunDispatchPlanner.Status;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Membership;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Outbox;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Progress;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Run;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;

class JobRunDispatchPlannerTest {
    private static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofSeconds(5);
    private static final Scope GLOBAL = new Scope(ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2));
    private final JobRunDispatchPlanner planner = new JobRunDispatchPlanner();

    @Test
    void hundredItemsBudget75SendsRemainderThenWaitsForEveryExecutionSuccess() {
        UUID run = UUID.randomUUID();
        for (int sent : List.of(0, 75, 100)) {
            Run state =
                    new Run(
                            run,
                            "RUNNING",
                            3,
                            List.of(
                                    step(0, List.of(), 100 - sent, sent, 0),
                                    step(1, List.of(0), 100, 0, 0),
                                    step(2, List.of(1), 100, 0, 0)));
            var plan = plan(75, state);
            if (sent == 100) {
                assertThat(plan.status()).isEqualTo(Status.WAIT);
                assertThat(plan.selections()).isEmpty();
            } else {
                assertThat(plan.selections())
                        .singleElement()
                        .satisfies(
                                selection -> {
                                    assertThat(selection.stepIndex()).isZero();
                                    assertThat(selection.quota())
                                            .isEqualTo(Math.min(75, 100 - sent));
                                });
            }
        }
        Run aDone =
                new Run(
                        run,
                        "RUNNING",
                        3,
                        List.of(
                                step(0, List.of(), 0, 100, 101),
                                step(1, List.of(0), 100, 0, 0),
                                step(2, List.of(1), 100, 0, 0)));
        assertThat(plan(75, aDone).selections())
                .singleElement()
                .satisfies(
                        selection -> {
                            assertThat(selection.stepIndex()).isEqualTo(1);
                            assertThat(selection.reason())
                                    .isEqualTo(JobRunDispatchPlanner.Reason.PREDECESSORS_SUCCEEDED);
                        });
    }

    @Test
    void joinsRequireAllPredecessorsAndParentSuccessCannotHideRunningChildren() {
        var root = step(0, List.of(), 0, 1, 2);
        var other = step(1, List.of(), 0, 1, 1);
        var target = step(2, List.of(0, 1), 1, 0, 0);
        assertThat(plan(10, run(root, other, target)).status()).isEqualTo(Status.WAIT);
        var done = step(1, List.of(), 0, 1, 2);
        assertThat(plan(10, run(root, done, target)).selections())
                .singleElement()
                .satisfies(selection -> assertThat(selection.stepIndex()).isEqualTo(2));
        var failed =
                new Step(
                        1,
                        UUID.randomUUID(),
                        GLOBAL,
                        List.of(),
                        new Membership(true, true, 1, 1, 1, 2, 0, 1, 0),
                        new Outbox(0, 0, 0, 1, 0, 0));
        assertThat(plan(10, run(root, failed, target)).status()).isEqualTo(Status.WAIT);
    }

    @Test
    void unusedQuotaFlowsToOtherRunsAndWaitingRunsDoNotConsumeBudget() {
        Run one = run(step(0, List.of(), 1, 0, 0));
        Run many = run(step(0, List.of(), 10, 0, 0));
        Run waiting = run(step(0, List.of(), 0, 1, 0));
        var result = plan(10, one, waiting, many);
        assertThat(result.selections())
                .extracting(JobRunDispatchPlanner.Selection::quota)
                .containsExactly(1, 9);
        assertThat(result.selections())
                .extracting(JobRunDispatchPlanner.Selection::jobRunId)
                .containsExactly(one.id(), many.id());
    }

    @Test
    void cursorFollowsLastAllocatedTurnIncludingBudgetLargerThanRunCount() {
        Run a = run(step(0, List.of(), 10, 0, 0));
        Run b = run(step(0, List.of(), 10, 0, 0));
        Run c = run(step(0, List.of(), 10, 0, 0));
        var snapshot = snapshot(a, b, c);
        assertThat(planner.plan(snapshot, 4, null, NOW, MAX_AGE).nextCursor()).isEqualTo(b.id());
        UUID cursor = null;
        for (Run expected : List.of(a, b, c, a)) {
            var next = planner.plan(snapshot, 1, cursor, NOW, MAX_AGE);
            assertThat(next.selections())
                    .singleElement()
                    .satisfies(
                            selection -> assertThat(selection.jobRunId()).isEqualTo(expected.id()));
            cursor = next.nextCursor();
        }
        assertThat(
                        planner.plan(snapshot, 1, UUID.randomUUID(), NOW, MAX_AGE)
                                .selections()
                                .getFirst()
                                .jobRunId())
                .isEqualTo(a.id());
    }

    @Test
    void siblingRootsShareRunTurnWithoutGivingMoreWeightThanAnotherRun() {
        Run join =
                run(
                        step(0, List.of(), 1, 0, 0),
                        step(1, List.of(), 9, 0, 0),
                        step(2, List.of(0, 1), 1, 0, 0));
        Run other = run(step(0, List.of(), 10, 0, 0));
        var result = plan(8, join, other);
        assertThat(
                        result.selections().stream()
                                .filter(selection -> selection.jobRunId().equals(join.id()))
                                .mapToInt(JobRunDispatchPlanner.Selection::quota)
                                .sum())
                .isEqualTo(4);
        assertThat(
                        result.selections().stream()
                                .filter(selection -> selection.jobRunId().equals(other.id()))
                                .mapToInt(JobRunDispatchPlanner.Selection::quota)
                                .sum())
                .isEqualTo(4);
        assertThat(result.selections()).noneMatch(selection -> selection.stepIndex() == 2);
    }

    @Test
    void typedScopesAndDatesRemainDistinctEvenForSameDefinition() {
        UUID definition = UUID.randomUUID();
        List<Run> runs = new ArrayList<>();
        for (ScopeType type : ScopeType.values()) {
            for (LocalDate date :
                    List.of(GLOBAL.businessDate(), GLOBAL.businessDate().plusDays(1))) {
                Step base = step(0, List.of(), 1, 0, 0);
                runs.add(
                        run(
                                new Step(
                                        0,
                                        definition,
                                        new Scope(type, "HOSE:HPG", date),
                                        List.of(),
                                        base.membership(),
                                        base.outbox())));
            }
        }
        var result = plan(100, runs.toArray(Run[]::new));
        assertThat(result.selections()).hasSize(runs.size());
        assertThat(result.selections())
                .extracting(JobRunDispatchPlanner.Selection::jobRunId)
                .doesNotHaveDuplicates();
        assertThat(result.selections())
                .extracting(JobRunDispatchPlanner.Selection::scope)
                .doesNotHaveDuplicates();
    }

    @Test
    void staleFutureIncompleteAndInvalidInputsNeverAllocate() {
        var snapshot = snapshot(run(step(0, List.of(), 1, 0, 0)));
        assertThat(planner.plan(snapshot, 1, null, NOW.plus(MAX_AGE), MAX_AGE).status())
                .isEqualTo(Status.PLANNED);
        for (Instant invalid : List.of(NOW.minusNanos(1), NOW.plus(MAX_AGE).plusNanos(1))) {
            assertThat(planner.plan(snapshot, 1, null, invalid, MAX_AGE).status())
                    .isEqualTo(Status.STALE);
        }
        assertThat(
                        planner.plan(
                                        new JobRunDispatchSnapshot(NOW, false, snapshot.runs()),
                                        1,
                                        null,
                                        NOW,
                                        MAX_AGE)
                                .status())
                .isEqualTo(Status.INCOMPLETE);
        for (int invalid : List.of(-1, 0, 101)) {
            assertThatThrownBy(() -> planner.plan(snapshot, invalid, null, NOW, MAX_AGE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> planner.plan(snapshot, 1, null, NOW, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(plan(1).status()).isEqualTo(Status.WAIT);
    }

    @Test
    void malformedOrDisconnectedPathsCannotBeInterpretedAsReady() {
        Step root = step(0, List.of(), 1, 0, 0);
        Step target = step(1, List.of(0), 1, 0, 0);
        for (Run invalid :
                List.of(
                        new Run(UUID.randomUUID(), "CANCELLED", 1, List.of(root)),
                        new Run(UUID.randomUUID(), "SUCCESS", 1, List.of(root)),
                        new Run(UUID.randomUUID(), "PREPARED", 0, List.of()),
                        new Run(UUID.randomUUID(), "PREPARED", 1025, List.of()),
                        new Run(UUID.randomUUID(), "PREPARED", 2, List.of(root)),
                        run(target),
                        run(root, step(1, List.of(), 1, 0, 0)),
                        run(root, step(1, List.of(1), 1, 0, 0)),
                        run(root, step(1, List.of(-1), 1, 0, 0)),
                        run(root, step(1, List.of(0, 0), 1, 0, 0)),
                        run(
                                root,
                                new Step(
                                        1,
                                        target.definitionId(),
                                        new Scope(
                                                ScopeType.GLOBAL,
                                                "VN",
                                                GLOBAL.businessDate().plusDays(1)),
                                        List.of(0),
                                        target.membership(),
                                        target.outbox())))) {
            assertThat(plan(1, invalid).status()).isEqualTo(Status.INCOMPLETE);
        }
    }

    @Test
    void closureIdentityCountsAndUnknownEvidenceFailClosed() {
        var valid = step(0, List.of(), 1, 0, 0);
        for (Membership invalid :
                List.of(
                        new Membership(false, true, 1, 1, 1, 2, 0, 0, 0),
                        new Membership(true, false, 1, 1, 1, 2, 0, 0, 0),
                        new Membership(true, true, 1, 1, 0, 2, 0, 0, 0),
                        new Membership(true, true, -1, 1, 1, 2, 0, 0, 0),
                        new Membership(true, true, 1, -1, 1, 2, 0, 0, 0),
                        new Membership(true, true, 2, 1, 1, 2, 0, 0, 0),
                        new Membership(true, true, 1, 2, 1, 2, 0, 0, 0),
                        new Membership(true, true, 1, 1, 1, 2, 0, 0, 1))) {
            var step =
                    new Step(0, valid.definitionId(), GLOBAL, List.of(), invalid, valid.outbox());
            assertThat(step.progress()).isEqualTo(Progress.UNKNOWN);
            assertThat(plan(1, run(step)).selections()).isEmpty();
        }
        assertThat(
                        new Step(
                                        0,
                                        valid.definitionId(),
                                        GLOBAL,
                                        List.of(),
                                        valid.membership(),
                                        new Outbox(0, 0, 0, 0, 0, 1))
                                .progress())
                .isEqualTo(Progress.UNKNOWN);
        assertThat(
                        new Step(
                                        0,
                                        valid.definitionId(),
                                        GLOBAL,
                                        List.of(),
                                        valid.membership(),
                                        new Outbox(0, 0, 0, 0, 1, 0))
                                .progress())
                .isEqualTo(Progress.FAILED);
        Run unknown =
                new Run(
                        UUID.randomUUID(),
                        "RUNNING",
                        1,
                        List.of(
                                new Step(
                                        0,
                                        valid.definitionId(),
                                        GLOBAL,
                                        List.of(),
                                        new Membership(false, true, 1, 1, 1, 2, 0, 0, 0),
                                        valid.outbox())));
        Run independent = run(valid);
        assertThat(plan(1, unknown, independent).selections())
                .singleElement()
                .satisfies(
                        selection -> assertThat(selection.jobRunId()).isEqualTo(independent.id()));
    }

    @Test
    void emptyClosedStepRequiresExplicitSuccessfulParentAndLeasesDelaysCannotDispatch() {
        Membership done = new Membership(true, true, 0, 0, 1, 1, 1, 0, 0);
        Step empty =
                new Step(
                        0,
                        UUID.randomUUID(),
                        GLOBAL,
                        List.of(),
                        done,
                        new Outbox(0, 0, 0, 0, 0, 0));
        assertThat(empty.progress()).isEqualTo(Progress.SUCCESS);
        assertThat(plan(1, run(empty, step(1, List.of(0), 1, 0, 0))).selections())
                .singleElement()
                .satisfies(selection -> assertThat(selection.stepIndex()).isEqualTo(1));
        var membership = new Membership(true, true, 2, 2, 1, 3, 0, 0, 0);
        var delayed =
                new Step(
                        0,
                        UUID.randomUUID(),
                        GLOBAL,
                        List.of(),
                        membership,
                        new Outbox(0, 1, 1, 0, 0, 0));
        assertThat(plan(10, run(delayed)).status()).isEqualTo(Status.WAIT);
    }

    @Test
    void snapshotAndPlanCollectionsAreImmutableAndDuplicateRunsRejected() {
        Run run = run(step(0, List.of(), 1, 0, 0));
        var snapshot = snapshot(run);
        assertThatThrownBy(() -> snapshot.runs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> run.steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> run.steps().getFirst().predecessors().add(0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan(1, run).selections().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot(run, run)).isInstanceOf(IllegalArgumentException.class);
        for (int index = 0; index < 6; index++) {
            long[] counts = new long[6];
            counts[index] = -1;
            assertThatThrownBy(
                            () ->
                                    new Outbox(
                                            counts[0], counts[1], counts[2], counts[3], counts[4],
                                            counts[5]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private JobRunDispatchPlanner.Plan plan(int budget, Run... runs) {
        return planner.plan(snapshot(runs), budget, null, NOW, MAX_AGE);
    }

    private static JobRunDispatchSnapshot snapshot(Run... runs) {
        return new JobRunDispatchSnapshot(NOW, true, List.of(runs));
    }

    private static Run run(Step... steps) {
        return new Run(UUID.randomUUID(), "RUNNING", steps.length, List.of(steps));
    }

    private static Step step(
            int index, List<Integer> predecessors, int eligible, int published, long successful) {
        int total = eligible + published;
        return new Step(
                index,
                UUID.randomUUID(),
                GLOBAL,
                predecessors,
                new Membership(true, true, total, total, 1, total + 1L, successful, 0, 0),
                new Outbox(eligible, 0, 0, published, 0, 0));
    }
}
