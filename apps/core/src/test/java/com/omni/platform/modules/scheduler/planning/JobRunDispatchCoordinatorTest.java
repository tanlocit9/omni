package com.omni.platform.modules.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.*;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchStore.*;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxCandidate;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;

class JobRunDispatchCoordinatorTest {
    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    final JobRunDispatchStore store = mock(JobRunDispatchStore.class);
    final JobRunDispatchSnapshotReader reader = mock(JobRunDispatchSnapshotReader.class);
    final SchedulerOutboxService outbox = mock(SchedulerOutboxService.class);

    @Test
    void explicitRollbackCannotBeCombinedWithPreparationAndNeverBecomesImplicitFallback() {
        assertThatThrownBy(() -> new JobRunDispatchCoordinator(store, reader, outbox, false, true))
                .hasMessageContaining("requires guarded");
        assertThatThrownBy(() -> new JobRunDispatchCoordinator(null, reader, outbox, true, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobRunDispatchCoordinator(store, null, outbox, true, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobRunDispatchCoordinator(store, reader, null, true, false))
                .isInstanceOf(NullPointerException.class);
        var legacy = new JobRunDispatchCoordinator(null, null, outbox, false, false);
        var candidate = candidate();
        when(outbox.findCandidates(NOW, 4)).thenReturn(List.of(candidate));
        var batch = legacy.load(NOW, 1);
        assertThat(batch.mode()).isEqualTo(JobRunDispatchCoordinator.Mode.DISABLED);
        legacy.claim(batch.candidates().getFirst(), NOW, "owner", Duration.ofSeconds(30), Map.of());
        verify(outbox)
                .claimEligible(
                        candidate.messageId(), NOW, "owner", Duration.ofSeconds(30), Map.of());
        for (int invalid : List.of(0, 101))
            assertThatThrownBy(() -> legacy.load(NOW, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledLegacyLaneUsesStrictIdentityRevalidation() {
        var legacy = candidate();
        when(store.findLegacyCandidates(NOW, 8)).thenReturn(List.of(legacy));
        var coordinator = new JobRunDispatchCoordinator(store, reader, outbox, true, false);
        var batch = coordinator.load(NOW, 2);
        assertThat(batch.mode()).isEqualTo(JobRunDispatchCoordinator.Mode.PLANNED);
        coordinator.claim(
                batch.candidates().getFirst(), NOW, "owner", Duration.ofSeconds(30), Map.of());
        verify(store)
                .claimLegacy(legacy.messageId(), NOW, "owner", Duration.ofSeconds(30), Map.of());
        verifyNoInteractions(outbox);
    }

    @Test
    void budgetOneAlternatesLegacyWithoutSkippingManagedRunThatWasNeverVisited() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var managedA = candidate();
        var managedB = candidate();
        var legacy = candidate();
        when(store.findLegacyCandidates(NOW, 4)).thenReturn(List.of(legacy));
        when(store.findRunPage(eq(NOW), any(), eq(32)))
                .thenAnswer(call -> a.equals(call.getArgument(1)) ? List.of(b, a) : List.of(a, b));
        when(reader.capture(eq(NOW), anyList()))
                .thenAnswer(
                        call ->
                                new JobRunDispatchSnapshot(
                                        NOW,
                                        true,
                                        ((List<UUID>) call.getArgument(1))
                                                .stream()
                                                        .map(
                                                                id ->
                                                                        run(
                                                                                id,
                                                                                id.equals(a)
                                                                                        ? managedA
                                                                                        : managedB,
                                                                                1))
                                                        .toList()));
        when(store.findScopedCandidates(any(), eq(NOW), anyInt()))
                .thenAnswer(
                        call ->
                                ((JobRunDispatchPlanner.Selection) call.getArgument(0))
                                                .jobRunId()
                                                .equals(a)
                                        ? List.of(managedA)
                                        : List.of(managedB));
        var coordinator = new JobRunDispatchCoordinator(store, reader, outbox, true, false);
        for (UUID expected :
                List.of(
                        managedA.messageId(),
                        legacy.messageId(),
                        managedB.messageId(),
                        legacy.messageId())) {
            var first = coordinator.load(NOW, 1).candidates().getFirst();
            assertThat(first.candidate().messageId()).isEqualTo(expected);
            coordinator.claim(first, NOW, "owner", Duration.ofSeconds(30), Map.of());
        }
        verify(outbox, never()).findCandidates(any(), anyInt());
    }

    @Test
    void primaryQuotasPrecedeBoundedOverflowAndUnusedSlotsRemainAvailable() {
        UUID id = UUID.randomUUID();
        var managed = candidate();
        var legacy = List.of(candidate(), candidate(), candidate(), candidate());
        when(store.findLegacyCandidates(NOW, 16)).thenReturn(legacy);
        when(store.findRunPage(eq(NOW), any(), eq(32))).thenReturn(List.of(id));
        when(reader.capture(NOW, List.of(id)))
                .thenReturn(new JobRunDispatchSnapshot(NOW, true, List.of(run(id, managed, 1))));
        when(store.findScopedCandidates(any(), eq(NOW), anyInt())).thenReturn(List.of(managed));
        var batch = new JobRunDispatchCoordinator(store, reader, outbox, true, false).load(NOW, 4);
        assertThat(batch.candidates())
                .extracting(p -> p.candidate().messageId())
                .containsExactly(
                        managed.messageId(),
                        legacy.get(0).messageId(),
                        legacy.get(1).messageId(),
                        legacy.get(2).messageId(),
                        legacy.get(3).messageId());
    }

    @Test
    void incompleteOrStaleSnapshotsUsePaginatedGuardedFallbackAndKeepStrictClaim() {
        UUID run = UUID.randomUUID();
        var candidate = candidate();
        var selected =
                new JobRunDispatchPlanner.Selection(
                        run,
                        0,
                        candidate.jobDefinition().getId(),
                        scope(),
                        1,
                        JobRunDispatchPlanner.Reason.ROOT_STEP);
        var envelope = new PlannedCandidate(candidate, selected);
        var cursor = new FallbackCursor(NOW, NOW, candidate.messageId());
        when(store.findRunPage(eq(NOW), any(), eq(32))).thenReturn(List.of(run));
        when(store.findFallbackCandidates(eq(NOW), any(), eq(4)))
                .thenReturn(new FallbackBatch(List.of(envelope), cursor));
        for (var snapshot :
                List.of(
                        new JobRunDispatchSnapshot(NOW, false, List.of()),
                        new JobRunDispatchSnapshot(
                                NOW.minusSeconds(10), true, List.of(run(run, candidate, 1))))) {
            when(reader.capture(NOW, List.of(run))).thenReturn(snapshot);
            var coordinator = new JobRunDispatchCoordinator(store, reader, outbox, true, false);
            var batch = coordinator.load(NOW, 1);
            assertThat(batch.mode()).isEqualTo(JobRunDispatchCoordinator.Mode.FALLBACK);
            coordinator.claim(envelope, NOW, "owner", Duration.ofSeconds(30), Map.of());
            coordinator.load(NOW, 1);
        }
        verify(store, times(2))
                .claimPlanned(
                        selected,
                        candidate.messageId(),
                        NOW,
                        "owner",
                        Duration.ofSeconds(30),
                        Map.of());
        verify(store, times(2)).findFallbackCandidates(NOW, cursor, 4);
        verifyNoInteractions(outbox);
    }

    @Test
    void databaseFailureCannotFallThroughToHistoricalUnscopedClaims() {
        when(store.findLegacyCandidates(any(), anyInt()))
                .thenThrow(new IllegalStateException("database"));
        when(store.findFallbackCandidates(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("database"));
        var batch = new JobRunDispatchCoordinator(store, reader, outbox, true, false).load(NOW, 10);
        assertThat(batch.mode()).isEqualTo(JobRunDispatchCoordinator.Mode.FAILED);
        assertThat(batch.candidates()).isEmpty();
        verifyNoInteractions(outbox);
    }

    @Test
    void emptyAndPoisonOnlyManagedScopesAdvanceDiscoveryInsteadOfStarvingFollowingRuns() {
        var coordinator = new JobRunDispatchCoordinator(store, reader, outbox, true, false);
        assertThat(coordinator.load(NOW, 1).candidates()).isEmpty();
        UUID id = UUID.randomUUID();
        var candidate = candidate();
        when(store.findRunPage(eq(NOW), any(), eq(32))).thenReturn(List.of(id));
        when(reader.capture(NOW, List.of(id)))
                .thenReturn(new JobRunDispatchSnapshot(NOW, true, List.of(run(id, candidate, 1))));
        coordinator.load(NOW, 1);
        coordinator.load(NOW, 1);
        verify(store).findRunPage(NOW, id, 32);
        // A closed running run with only published unfinished work advances the discovery cursor
        // too.
        var step =
                new Step(
                        0,
                        candidate.jobDefinition().getId(),
                        scope(),
                        List.of(),
                        new Membership(true, true, 1, 1, 1, 2, 0, 0, 0),
                        new Outbox(0, 0, 0, 1, 0, 0));
        when(reader.capture(NOW, List.of(id)))
                .thenReturn(
                        new JobRunDispatchSnapshot(
                                NOW, true, List.of(new Run(id, "RUNNING", 1, List.of(step)))));
        assertThat(coordinator.load(NOW, 1).candidates()).isEmpty();
    }

    static Run run(UUID id, SchedulerOutboxCandidate candidate, int count) {
        return new Run(
                id,
                "RUNNING",
                1,
                List.of(
                        new Step(
                                0,
                                candidate.jobDefinition().getId(),
                                scope(),
                                List.of(),
                                new Membership(true, true, count, count, 1, count + 1, 0, 0, 0),
                                new Outbox(count, 0, 0, 0, 0, 0))));
    }

    @Test
    void configurationBindingFailsClosedForUnsafePreparationRollbackCombination() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(JobRunDispatchStore.class, () -> store)
                .withBean(JobRunDispatchSnapshotReader.class, () -> reader)
                .withBean(SchedulerOutboxService.class, () -> outbox)
                .withBean(JobRunDispatchCoordinator.class)
                .withPropertyValues(
                        "app.scheduler.job-run.planning-enabled=false",
                        "app.scheduler.job-run.preparation-enabled=true")
                .run(
                        context ->
                                assertThat(context.getStartupFailure())
                                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    static Scope scope() {
        return new Scope(ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2));
    }

    static SchedulerOutboxCandidate candidate() {
        var definition = new JobDefinition();
        definition.setId(UUID.randomUUID());
        return new SchedulerOutboxCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                definition,
                "GLOBAL",
                "VN",
                "key",
                Map.of(),
                NOW);
    }
}
