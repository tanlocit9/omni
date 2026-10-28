package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchPlanner;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchPlanner.Selection;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshotReader;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchStore;
import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

/** Actual JPA/JDBC transactions and fencing over disposable PostgreSQL; outbound work disabled. */
@Testcontainers
@Timeout(60)
class JobRunAdmissionPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static JobRunDispatchStore store;
    static JobRunDispatchSnapshotReader snapshots;
    static JobRunRepository runs;
    static SchedulerOutboxService outbox;

    @BeforeAll
    static void start() {
        context = PlatformStartupPostgresTest.startApplication(POSTGRES, false);
        jdbc = context.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        store = context.getBean(JobRunDispatchStore.class);
        snapshots = context.getBean(JobRunDispatchSnapshotReader.class);
        runs = context.getBean(JobRunRepository.class);
        outbox = context.getBean(SchedulerOutboxService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE job_runs,job_execution_histories CASCADE");
        jdbc.update(
                "DELETE FROM job_definitions WHERE source='VND' AND cron_expr IN ('a','b','target')");
        for (String cron : List.of("a", "b", "target")) {
            jdbc.update(
                    "INSERT INTO job_definitions(title,source,job_type,cron_expr) VALUES (?,'VND','SYNC_SYMBOLS',?)",
                    cron,
                    cron);
        }
    }

    @Test
    void stalePlanWaitsWithoutAttemptOrApprovedInputUntilEveryAncestorSucceeds() {
        UUID run = prepare(false, true);
        Selection target = selection(run, 2);
        UUID message = message(run, 2);
        var input = DatasetRef.of("eod", Map.of("exchange", "HOSE"));
        assertThat(
                        store.claimPlanned(
                                target,
                                message,
                                NOW,
                                "owner",
                                Duration.ofSeconds(30),
                                Map.of(input, "v1")))
                .isNull();
        assertThat(attempts(message)).isZero();
        succeed(run, 1); // Out-of-order worker success must not hide unfinished root A.
        assertThat(claim(target, message, "owner")).isNull();
        succeed(run, 0);
        var claim =
                store.claimPlanned(
                        target, message, NOW, "owner", Duration.ofSeconds(30), Map.of(input, "v1"));
        assertThat(claim).isNotNull();
        assertThat(attempts(message)).isEqualTo(1);
        String metadata =
                jdbc.queryForObject(
                        "SELECT meta_json::text FROM job_execution_histories WHERE id=?",
                        String.class,
                        claim.executionId());
        assertThat(metadata).contains("approvedInputs", "v1");
    }

    @Test
    void joinRequiresBothClosedRootsAndRejectsFailedAncestor() {
        UUID run = prepare(true, true);
        Selection target = selection(run, 2);
        UUID message = message(run, 2);
        succeed(run, 0);
        assertThat(claim(target, message, "owner")).isNull();
        jdbc.update(
                "UPDATE job_execution_histories SET status='FAILED' WHERE job_run_id=? AND job_run_step_index=1",
                run);
        assertThat(claim(target, message, "owner")).isNull();
        succeed(run, 1);
        assertThat(claim(target, message, "owner")).isNotNull();
    }

    @Test
    void scopedQueriesAndUuidPageWrapDoNotMixRunsScopesOrLegacyRows() {
        assertThat(store.findRunPage(NOW, null, 32)).isEmpty();
        UUID first = prepare(false, true);
        UUID second = prepare(false, true);
        Selection a = selection(first, 0);
        var statistics =
                context.getBean(jakarta.persistence.EntityManagerFactory.class)
                        .unwrap(org.hibernate.SessionFactory.class)
                        .getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        assertThat(store.findScopedCandidates(a, NOW, 400))
                .singleElement()
                .satisfies(
                        candidate ->
                                assertThat(candidate.messageId()).isEqualTo(message(first, 0)));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        statistics.setStatisticsEnabled(false);
        assertThat(store.findLegacyCandidates(NOW, 400)).isEmpty();
        List<UUID> ordered =
                List.of(first, second).stream()
                        .sorted(java.util.Comparator.comparing(UUID::toString))
                        .toList();
        assertThat(store.findRunPage(NOW, null, 1)).containsExactly(ordered.getFirst());
        assertThat(store.findRunPage(NOW, ordered.getFirst(), 1))
                .containsExactly(ordered.getLast());
        assertThat(store.findRunPage(NOW, ordered.getLast(), 32))
                .containsExactlyElementsOf(ordered);
        assertThat(store.findRunPage(NOW, ordered.getFirst(), 32))
                .containsExactly(ordered.getLast(), ordered.getFirst());
        var wrong =
                new Selection(
                        a.jobRunId(),
                        0,
                        a.definitionId(),
                        new Scope(ScopeType.GLOBAL, "OTHER", a.scope().businessDate()),
                        1,
                        a.reason());
        assertThat(store.findScopedCandidates(wrong, NOW, 10)).isEmpty();
        UUID legacy = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,meta_json) VALUES (?,?,'VND','RUNNING','{\"workType\":\"GLOBAL\",\"workKey\":\"VN\"}')",
                legacy,
                a.definitionId());
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'test',?,?)",
                legacy,
                "{\"executionId\":\"" + legacy + "\"}",
                java.sql.Timestamp.from(NOW));
        assertThat(store.findLegacyCandidates(NOW, 10))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.executionId()).isEqualTo(legacy));
        for (int invalid : List.of(0, 401)) {
            assertThatThrownBy(() -> store.findScopedCandidates(a, NOW, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.findLegacyCandidates(NOW, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int invalid : List.of(0, 33))
            assertThatThrownBy(() -> store.findRunPage(NOW, null, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forgedScopeDefinitionMessageAndUnknownMembershipNeverClaim() {
        UUID run = prepare(false, true);
        Selection a = selection(run, 0);
        UUID message = message(run, 0);
        assertThat(
                        claim(
                                new Selection(
                                        a.jobRunId(),
                                        0,
                                        UUID.randomUUID(),
                                        a.scope(),
                                        1,
                                        a.reason()),
                                message,
                                "owner"))
                .isNull();
        assertThat(
                        claim(
                                new Selection(
                                        a.jobRunId(),
                                        0,
                                        a.definitionId(),
                                        new Scope(
                                                ScopeType.GLOBAL,
                                                "OTHER",
                                                a.scope().businessDate()),
                                        1,
                                        a.reason()),
                                message,
                                "owner"))
                .isNull();
        assertThat(
                        claim(
                                new Selection(
                                        UUID.randomUUID(),
                                        0,
                                        a.definitionId(),
                                        a.scope(),
                                        1,
                                        a.reason()),
                                message,
                                "owner"))
                .isNull();
        assertThat(
                        claim(
                                new Selection(
                                        run, 1023, a.definitionId(), a.scope(), 1, a.reason()),
                                message,
                                "owner"))
                .isNull();
        for (int invalid : List.of(-1, 1024))
            assertThatThrownBy(
                            () ->
                                    claim(
                                            new Selection(
                                                    run,
                                                    invalid,
                                                    a.definitionId(),
                                                    a.scope(),
                                                    1,
                                                    a.reason()),
                                            message,
                                            "owner"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(claim(a, message(run, 1), "owner")).isNull();
        assertThat(claim(a, UUID.randomUUID(), "owner")).isNull();
        UUID unknown = prepare(false, false);
        assertThat(claim(selection(unknown, 0), message, "owner")).isNull();
        succeed(run, 0);
        assertThat(claim(a, message, "owner")).isNull();
        assertThat(attempts(message)).isZero();
    }

    @Test
    void concurrentClaimsLeaseRecoveryRetryAndAcknowledgementKeepExactFence() throws Exception {
        UUID run = prepare(false, true);
        Selection a = selection(run, 0);
        UUID message = message(run, 0);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return claim(a, message, "one");
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return claim(a, message, "two");
                            });
            start.countDown();
            var values = new ArrayList<SchedulerOutboxClaim>();
            values.add(first.get(10, TimeUnit.SECONDS));
            values.add(second.get(10, TimeUnit.SECONDS));
            assertThat(values.stream().filter(java.util.Objects::nonNull)).hasSize(1);
            var old = values.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertThat(claim(a, message, "other")).isNull();
            var recovered =
                    store.claimPlanned(
                            a,
                            message,
                            NOW.plusSeconds(31),
                            "new",
                            Duration.ofSeconds(30),
                            Map.of());
            assertThat(recovered).isNotNull();
            assertThat(recovered.claimToken()).isNotEqualTo(old.claimToken());
            assertThat(outbox.markDelivered(old, NOW.plusSeconds(32))).isFalse();
            assertThat(outbox.scheduleRetry(old, NOW.plusSeconds(32), "old")).isFalse();
            assertThat(outbox.scheduleRetry(recovered, NOW.plusSeconds(40), "retry")).isTrue();
            assertThat(
                            store.claimPlanned(
                                    a,
                                    message,
                                    NOW.plusSeconds(39),
                                    "early",
                                    Duration.ofSeconds(30),
                                    Map.of()))
                    .isNull();
            var retry =
                    store.claimPlanned(
                            a,
                            message,
                            NOW.plusSeconds(40),
                            "retry",
                            Duration.ofSeconds(30),
                            Map.of());
            assertThat(retry.payload()).isEqualTo(old.payload());
            assertThat(retry.executionId()).isEqualTo(old.executionId());
            assertThat(outbox.markDelivered(retry, NOW.plusSeconds(41))).isTrue();
            assertThat(
                            store.claimPlanned(
                                    a,
                                    message,
                                    NOW.plusSeconds(42),
                                    "terminal",
                                    Duration.ofSeconds(30),
                                    Map.of()))
                    .isNull();
        }
    }

    @Test
    void busyRunRootChildAndOutboxAreSkippedWithoutDeadlockOrAttempt() throws Exception {
        UUID run = prepare(false, true);
        Selection a = selection(run, 0);
        UUID message = message(run, 0);
        for (String sql :
                List.of(
                        "SELECT id FROM job_runs WHERE id=? FOR UPDATE",
                        "SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0 AND parent_log_id IS NULL FOR UPDATE",
                        "SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0 AND parent_log_id IS NOT NULL FOR UPDATE",
                        "SELECT o.id FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=0 FOR UPDATE OF o")) {
            whileLocked(sql, run, () -> assertThat(claim(a, message, "owner")).isNull());
            assertThat(attempts(message)).isZero();
        }
        assertThat(claim(a, message, "owner")).isNotNull();
    }

    @Test
    void predecessorMutationAndTerminalRunInvalidateSnapshotAtAdmission() throws Exception {
        UUID run = prepare(false, true);
        succeed(run, 0);
        succeed(run, 1);
        Selection target = selection(run, 2);
        UUID message = message(run, 2);
        whileLocked(
                "SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0 AND parent_log_id IS NULL FOR UPDATE",
                run,
                () -> assertThat(claim(target, message, "owner")).isNull());
        jdbc.update(
                "UPDATE job_execution_histories SET status='FAILED' WHERE job_run_id=? AND job_run_step_index=0",
                run);
        assertThat(claim(target, message, "owner")).isNull();
        succeed(run, 0);
        jdbc.update("UPDATE job_runs SET state='CANCELLED' WHERE id=?", run);
        assertThat(claim(target, message, "owner")).isNull();
        assertThat(attempts(message)).isZero();
    }

    @Test
    void metadataFailureRollsBackClaimAndAttempts() {
        UUID run = prepare(false, true);
        UUID message = message(run, 0);
        Map<DatasetRef, String> invalid = new java.util.HashMap<>();
        invalid.put(null, "v1");
        assertThatThrownBy(
                        () ->
                                store.claimPlanned(
                                        selection(run, 0),
                                        message,
                                        NOW,
                                        "owner",
                                        Duration.ofSeconds(30),
                                        invalid))
                .isInstanceOf(NullPointerException.class);
        assertThat(attempts(message)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT claim_token FROM scheduler_outbox_messages WHERE id=?",
                                UUID.class,
                                message))
                .isNull();
    }

    static void whileLocked(String sql, UUID run, Runnable action) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder =
                    executor.submit(
                            () ->
                                    tx.execute(
                                            status -> {
                                                jdbc.queryForList(sql, run);
                                                locked.countDown();
                                                try {
                                                    if (!release.await(10, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "Lock test timeout");
                                                } catch (InterruptedException error) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(error);
                                                }
                                                return true;
                                            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                action.run();
            } finally {
                release.countDown();
            }
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void integratedHundredItemBudget75TraceRequiresClosedSuccessBeforeEveryAdvance()
            throws Exception {
        UUID run = prepare(false, true, 100);
        var registry = readyRegistry();
        var kafka =
                org.mockito.Mockito.mock(
                        com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class);
        var coordinator = coordinator(snapshots);
        var dispatcher = dispatcher(75, registry, kafka, coordinator);
        tick(dispatcher);
        assertThat(published(run, 0)).isEqualTo(75);
        assertThat(published(run, 1)).isZero();
        tick(dispatcher);
        assertThat(published(run, 0)).isEqualTo(100);
        assertThat(published(run, 1)).isZero();
        tick(dispatcher);
        assertThat(published(run, 1)).isZero();
        succeed(run, 0);
        tick(dispatcher);
        assertThat(published(run, 1)).isEqualTo(75);
        assertThat(published(run, 2)).isZero();
        tick(dispatcher);
        assertThat(published(run, 1)).isEqualTo(100);
        assertThat(published(run, 2)).isZero();
        succeed(run, 1);
        tick(dispatcher);
        assertThat(published(run, 2)).isEqualTo(75);
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.times(275))
                .evaluate(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(kafka, org.mockito.Mockito.times(275))
                .publishSerializedAndWait(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=2 AND status='RUNNING'",
                                Long.class,
                                run))
                .isEqualTo(101L);
    }

    @Test
    void fallbackPagesRemainBarrierGuardedAcrossSnapshotFailuresAndRestart() {
        UUID run = prepare(false, true);
        var broken = org.mockito.Mockito.mock(JobRunDispatchSnapshotReader.class);
        org.mockito.Mockito.when(
                        broken.capture(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("snapshot unavailable"));
        var dispatcher =
                dispatcher(
                        10,
                        readyRegistry(),
                        org.mockito.Mockito.mock(
                                com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class),
                        coordinator(broken));
        tick(dispatcher);
        assertThat(published(run, 0)).isEqualTo(1);
        assertThat(published(run, 1)).isZero();
        assertThat(published(run, 2)).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(o.attempts) FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_run_id=?",
                                Long.class,
                                run))
                .isEqualTo(1);
        // New coordinator loses advisory cursors, not membership or completion authority.
        var restarted =
                dispatcher(
                        10,
                        readyRegistry(),
                        org.mockito.Mockito.mock(
                                com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class),
                        coordinator(snapshots));
        tick(restarted);
        assertThat(published(run, 1)).isZero();
        succeed(run, 0);
        tick(restarted);
        assertThat(published(run, 1)).isEqualTo(1);
        assertThat(published(run, 2)).isZero();
    }

    @Test
    void fifoFallbackCursorWrapsWithoutDuplicatesAndStrictLegacyClaimRejectsNewRunLinks() {
        assertThat(store.findFallbackCandidates(NOW, null, 2).candidates()).isEmpty();
        UUID run = prepare(false, true);
        var first = store.findFallbackCandidates(NOW, null, 2);
        assertThat(first.candidates()).hasSize(2);
        var second = store.findFallbackCandidates(NOW, first.nextCursor(), 2);
        assertThat(second.candidates()).hasSize(2);
        assertThat(second.candidates())
                .extracting(c -> c.candidate().messageId())
                .doesNotHaveDuplicates();
        var seen = new java.util.HashSet<UUID>();
        first.candidates().forEach(c -> seen.add(c.candidate().messageId()));
        second.candidates().forEach(c -> seen.add(c.candidate().messageId()));
        assertThat(seen).hasSize(3);
        assertThat(
                        store.claimLegacy(
                                message(run, 0), NOW, "legacy", Duration.ofSeconds(30), Map.of()))
                .isNull();
        var definition = selection(run, 0).definitionId();
        UUID history = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,meta_json) VALUES (?,?,'VND','RUNNING','{\"workType\":\"GLOBAL\",\"workKey\":\"VN\"}')",
                history,
                definition);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(id,execution_id,message_index,topic,payload,available_at) VALUES (?,?,0,'test',?,?)",
                id,
                history,
                "{\"executionId\":\"" + history + "\"}",
                java.sql.Timestamp.from(NOW));
        assertThat(store.findFallbackCandidates(NOW, null, 10).candidates())
                .anyMatch(candidate -> candidate.selection() == null);
        UUID poison = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(id,execution_id,message_index,topic,payload,available_at) VALUES (?,?,1,'test',?,?)",
                poison,
                history,
                "{\"executionId\":\""
                        + history
                        + "\",\"parentExecutionId\":\""
                        + UUID.randomUUID()
                        + "\"}",
                java.sql.Timestamp.from(NOW.minusSeconds(1)));
        var skipped = store.findFallbackCandidates(NOW, null, 1);
        assertThat(skipped.candidates()).isEmpty();
        assertThat(skipped.nextCursor()).isNotNull();
        assertThat(store.findFallbackCandidates(NOW, skipped.nextCursor(), 1).candidates())
                .hasSize(1);
        assertThat(
                        store.claimLegacy(
                                UUID.randomUUID(), NOW, "none", Duration.ofSeconds(30), Map.of()))
                .isNull();
        var legacy = store.claimLegacy(id, NOW, "legacy", Duration.ofSeconds(30), Map.of());
        assertThat(legacy).isNotNull();
        assertThat(store.claimLegacy(id, NOW, "busy", Duration.ofSeconds(30), Map.of())).isNull();
        outbox.scheduleRetry(legacy, NOW, "retry");
        UUID linked = prepare(false, false);
        jdbc.update(
                "UPDATE job_execution_histories SET job_run_id=?,job_run_step_index=0 WHERE id=?",
                linked,
                history);
        assertThat(store.claimLegacy(id, NOW, "stale", Duration.ofSeconds(30), Map.of())).isNull();
        assertThat(attempts(id)).isEqualTo(1);
    }

    @Test
    void waitingBlockedAndLostClaimsCannotConsumeOtherReadyScopeBudget() {
        UUID a = prepare(false, true),
                b = prepare(false, true),
                c = prepare(false, true),
                d = prepare(false, true);
        var registry =
                org.mockito.Mockito.mock(
                        com.omni.platform.modules.scheduler.dependencies.DependencyRegistry.class);
        org.mockito.Mockito.when(registry.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        call -> {
                            var request =
                                    (com.omni.platform.modules.scheduler.dependencies
                                                    .DependencyRequest)
                                            call.getArgument(0);
                            if (request.outboxMessageId().equals(message(a, 0)))
                                return com.omni.platform.modules.scheduler.dependencies
                                        .DependencyDecision.waiting(
                                        "WAIT", "fixture", NOW.plusSeconds(30));
                            if (request.outboxMessageId().equals(message(b, 0)))
                                return com.omni.platform.modules.scheduler.dependencies
                                        .DependencyDecision.blocked("BLOCKED", "fixture");
                            if (request.outboxMessageId().equals(message(c, 0))) {
                                jdbc.update("UPDATE job_runs SET state='CANCELLED' WHERE id=?", c);
                            }
                            return com.omni.platform.modules.scheduler.dependencies
                                    .DependencyDecision.ready(Map.of());
                        });
        tick(
                dispatcher(
                        4,
                        registry,
                        org.mockito.Mockito.mock(
                                com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class),
                        coordinator(snapshots)));
        assertThat(published(d, 0)).isEqualTo(1);
        assertThat(attempts(message(a, 0))).isZero();
        assertThat(attempts(message(b, 0))).isZero();
        assertThat(attempts(message(c, 0))).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scheduler_outbox_messages WHERE id=?",
                                String.class,
                                message(b, 0)))
                .isEqualTo("BLOCKED");
    }

    @Test
    void publishFailureRetriesSameIdentityAndLegacyLaneRemainsFairAtBudgetOne() throws Exception {
        UUID run = prepare(false, true);
        UUID history = UUID.randomUUID(), legacyId = UUID.randomUUID();
        var definition = selection(run, 0).definitionId();
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,meta_json) VALUES (?,?,'VND','RUNNING','{\"workType\":\"GLOBAL\",\"workKey\":\"VN\"}')",
                history,
                definition);
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(id,execution_id,message_index,topic,payload,available_at) VALUES (?,?,0,'test',?,?)",
                legacyId,
                history,
                "{\"executionId\":\"" + history + "\"}",
                java.sql.Timestamp.from(NOW));
        var kafka =
                org.mockito.Mockito.mock(
                        com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class);
        var coordinator = coordinator(snapshots);
        var dispatcher = dispatcher(1, readyRegistry(), kafka, coordinator);
        org.mockito.Mockito.doThrow(new RuntimeException("fixture transport failure"))
                .doNothing()
                .when(kafka)
                .publishSerializedAndWait(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        tick(dispatcher);
        assertThat(attempts(message(run, 0))).isEqualTo(1);
        assertThat(published(run, 0)).isZero();
        tick(dispatcher);
        assertThat(attempts(legacyId)).isEqualTo(1);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                dispatcher, "dispatchBatch", NOW.plusSeconds(31));
        assertThat(published(run, 0)).isEqualTo(1);
        assertThat(attempts(message(run, 0))).isEqualTo(2);
    }

    @Test
    void scopedSqlExplainIsBoundedWithActualSourceQueryAndDoesNotClaim() throws Exception {
        UUID run = prepare(false, true, 100);
        var selected = selection(run, 0);
        var field = JobRunDispatchStore.class.getDeclaredField("SCOPED_IDS_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);
        var parameters =
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("run", run)
                        .addValue("step", 0)
                        .addValue("definition", selected.definitionId())
                        .addValue("type", selected.scope().type().name())
                        .addValue("key", selected.scope().key())
                        .addValue("date", selected.scope().businessDate())
                        .addValue("now", java.sql.Timestamp.from(NOW))
                        .addValue("limit", 50);
        var template =
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        String report =
                template.queryForObject(
                        "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + sql, parameters, String.class);
        var parsed =
                context.getBean("jsonMapper", tools.jackson.databind.json.JsonMapper.class)
                        .readTree(report)
                        .get(0);
        assertThat(parsed.get("Plan").get("Actual Rows").asInt()).isEqualTo(50);
        assertThat(parsed.get("Plan").has("Shared Hit Blocks")).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(attempts) FROM scheduler_outbox_messages", Long.class))
                .isZero();
        var path = java.nio.file.Path.of("build/reports/job-run-dispatch/scoped-explain.json");
        java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path, report);
    }

    static com.omni.platform.modules.scheduler.planning.JobRunDispatchCoordinator coordinator(
            JobRunDispatchSnapshotReader reader) {
        return new com.omni.platform.modules.scheduler.planning.JobRunDispatchCoordinator(
                store, reader, outbox, true, false);
    }

    @Test
    void expiredOrSupersededPublishLeaseCannotBeRenewed() {
        UUID run = prepare(false, true);
        UUID message = message(run, 0);
        var old = claim(selection(run, 0), message, "old");
        assertThat(outbox.renewForPublish(old, NOW.plusSeconds(31), Duration.ofSeconds(30)))
                .isFalse();
        var current =
                store.claimPlanned(
                        selection(run, 0),
                        message,
                        NOW.plusSeconds(31),
                        "new",
                        Duration.ofSeconds(30),
                        Map.of());
        assertThat(outbox.renewForPublish(old, NOW.plusSeconds(32), Duration.ofSeconds(30)))
                .isFalse();
        assertThat(outbox.renewForPublish(current, NOW.plusSeconds(32), Duration.ofSeconds(30)))
                .isTrue();
        var foreign =
                new SchedulerOutboxClaim(
                        current.messageId(),
                        current.claimToken(),
                        "other",
                        current.executionId(),
                        current.topic(),
                        current.key(),
                        current.payload(),
                        current.attempts());
        assertThat(outbox.renewForPublish(foreign, NOW.plusSeconds(33), Duration.ofSeconds(30)))
                .isFalse();
        assertThat(outbox.markDelivered(current, NOW.plusSeconds(34))).isTrue();
        assertThat(outbox.renewForPublish(current, NOW.plusSeconds(35), Duration.ofSeconds(30)))
                .isFalse();
    }

    @Test
    void dispatcherRechecksFenceBeforeSendingLaterClaimInSameBatch() throws Exception {
        UUID run = prepare(false, true, 2);
        var kafka =
                org.mockito.Mockito.mock(
                        com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(
                        call -> {
                            if (calls.incrementAndGet() == 1) {
                                String payload = call.getArgument(2);
                                UUID execution =
                                        UUID.fromString(
                                                context.getBean(
                                                                "jsonMapper",
                                                                tools.jackson.databind.json
                                                                        .JsonMapper.class)
                                                        .readTree(payload)
                                                        .get("executionId")
                                                        .asText());
                                jdbc.update(
                                        "UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='new-owner',claim_until=? WHERE status='PENDING' AND claim_token IS NOT NULL AND execution_id<>?",
                                        UUID.randomUUID(),
                                        java.sql.Timestamp.from(Instant.now().plusSeconds(60)),
                                        execution);
                            }
                            return null;
                        })
                .when(kafka)
                .publishSerializedAndWait(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        tick(dispatcher(2, readyRegistry(), kafka, coordinator(snapshots)));
        assertThat(calls.get()).isEqualTo(1);
        assertThat(published(run, 0)).isEqualTo(1);
    }

    @Test
    void realSnapshotPreservesRotatedRunPageAtBudgetOne() {
        UUID a = prepare(false, true, 4);
        UUID b = prepare(false, true, 4);
        var page = store.findRunPage(NOW, null, 32);
        assertThat(page).containsExactlyInAnyOrder(a, b);
        var kafka =
                org.mockito.Mockito.mock(
                        com.omni.platform.shared.infrastructure.kafka.KafkaPublisher.class);
        var dispatcher = dispatcher(1, readyRegistry(), kafka, coordinator(snapshots));
        UUID first = page.getFirst(), second = page.getLast();
        tick(dispatcher);
        assertThat(published(first, 0)).isEqualTo(1);
        tick(dispatcher);
        assertThat(published(second, 0)).isEqualTo(1);
        tick(dispatcher);
        tick(dispatcher);
        assertThat(published(first, 0)).isEqualTo(2);
        assertThat(published(second, 0)).isEqualTo(2);
    }

    @Test
    void normalizedChildStatusCanFinishWhileApprovedInputAdmissionSkipsItsBusyWorkRow()
            throws Exception {
        UUID definition =
                jdbc.queryForObject(
                        "SELECT id FROM job_definitions WHERE source='VND' AND cron_expr='a'",
                        UUID.class);
        UUID parent = UUID.randomUUID(), child = UUID.randomUUID(), message = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,meta_json) VALUES (?,?,'VND','RUNNING','{}')",
                parent,
                definition);
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,parent_log_id,used_source,status,meta_json) VALUES (?,?,?,'VND','RUNNING','{\"workType\":\"EXCHANGE\",\"workKey\":\"HOSE\"}')",
                child,
                definition,
                parent);
        String payload =
                "{\"executionId\":\""
                        + child
                        + "\",\"parentExecutionId\":\""
                        + parent
                        + "\",\"workType\":\"EXCHANGE\",\"workKey\":\"HOSE\"}";
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(id,execution_id,message_index,topic,payload,available_at) VALUES (?,?,0,'test',?,?)",
                message,
                child,
                payload,
                java.sql.Timestamp.from(NOW));
        var locked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var approved = Map.of(DatasetRef.of("eod", Map.of("exchange", "HOSE")), "v1");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var worker =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            status -> {
                                                jdbc.queryForObject(
                                                        "SELECT id FROM job_execution_histories WHERE id=? FOR UPDATE",
                                                        UUID.class,
                                                        child);
                                                locked.countDown();
                                                try {
                                                    assertThat(finish.await(10, TimeUnit.SECONDS))
                                                            .isTrue();
                                                } catch (InterruptedException e) {
                                                    throw new RuntimeException(e);
                                                }
                                                context.getBean(
                                                                com.omni.platform.modules.scheduler
                                                                        .services.JobService.class)
                                                        .applyStatus(
                                                                new com.omni.platform.modules
                                                                        .scheduler.messaging
                                                                        .JobStatusMessage(
                                                                        definition.toString(),
                                                                        child.toString(),
                                                                        parent.toString(),
                                                                        com.omni.platform.shared
                                                                                .executions.WorkType
                                                                                .EXCHANGE,
                                                                        "HOSE",
                                                                        "SUCCESS",
                                                                        Map.of(),
                                                                        null,
                                                                        NOW,
                                                                        NOW,
                                                                        0,
                                                                        null,
                                                                        1));
                                            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                var admission =
                        pool.submit(
                                () ->
                                        store.claimLegacy(
                                                message,
                                                NOW,
                                                "legacy",
                                                Duration.ofSeconds(30),
                                                approved));
                assertThat(admission.get(3, TimeUnit.SECONDS)).isNull();
                assertThat(attempts(message)).isZero();
            } finally {
                finish.countDown();
            }
            worker.get(10, TimeUnit.SECONDS);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                child))
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                parent))
                .isEqualTo("SUCCESS");
        assertThat(store.claimLegacy(message, NOW, "legacy", Duration.ofSeconds(30), approved))
                .isNotNull();
        assertThat(attempts(message)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT meta_json::text FROM job_execution_histories WHERE id=?",
                                String.class,
                                child))
                .contains("v1");
    }

    static com.omni.platform.modules.scheduler.dependencies.DependencyRegistry readyRegistry() {
        var registry =
                org.mockito.Mockito.mock(
                        com.omni.platform.modules.scheduler.dependencies.DependencyRegistry.class);
        org.mockito.Mockito.when(registry.evaluate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        com.omni.platform.modules.scheduler.dependencies.DependencyDecision.ready(
                                Map.of()));
        return registry;
    }

    static com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher dispatcher(
            int budget,
            com.omni.platform.modules.scheduler.dependencies.DependencyRegistry registry,
            com.omni.platform.shared.infrastructure.kafka.KafkaPublisher kafka,
            com.omni.platform.modules.scheduler.planning.JobRunDispatchCoordinator coordinator) {
        return new com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher(
                outbox,
                registry,
                kafka,
                new com.omni.platform.modules.scheduler.config.SchedulerProperties(
                        "dispatcher-test",
                        new com.omni.platform.modules.scheduler.config.SchedulerProperties.Claim(
                                Duration.ofMinutes(2), budget)),
                coordinator);
    }

    static void tick(com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher dispatcher) {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                dispatcher, "dispatchBatch", NOW);
    }

    static long published(UUID run, int step) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=? AND o.status='PUBLISHED'",
                Long.class,
                run,
                step);
    }

    static SchedulerOutboxClaim claim(Selection selected, UUID message, String owner) {
        return store.claimPlanned(selected, message, NOW, owner, Duration.ofSeconds(30), Map.of());
    }

    static int attempts(UUID message) {
        return jdbc.queryForObject(
                "SELECT attempts FROM scheduler_outbox_messages WHERE id=?",
                Integer.class,
                message);
    }

    static UUID message(UUID run, int step) {
        return jdbc.queryForObject(
                "SELECT o.id FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=?",
                UUID.class,
                run,
                step);
    }

    static void succeed(UUID run, int step) {
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=?",
                run,
                step);
    }

    static Selection selection(UUID run, int index) {
        var step = snapshots.capture(NOW, List.of(run)).runs().getFirst().steps().get(index);
        return new Selection(
                run,
                index,
                step.definitionId(),
                step.scope(),
                1,
                JobRunDispatchPlanner.Reason.ROOT_STEP);
    }

    @Test
    void plannedSymbolExchangeAndGlobalMappingRemainRunAndDateIsolatedAtClaim() {
        // Stored-contract fixtures only. Actual producer support is still enforced by I3
        // preparation.
        UUID hpg =
                prepare(
                        false,
                        true,
                        1,
                        new Scope(ScopeType.SYMBOL, "HOSE:HPG", LocalDate.of(2035, 1, 2)));
        UUID fpt =
                prepare(
                        false,
                        true,
                        1,
                        new Scope(ScopeType.SYMBOL, "HOSE:FPT", LocalDate.of(2035, 1, 2)));
        UUID exchange =
                prepare(
                        false,
                        true,
                        1,
                        new Scope(ScopeType.EXCHANGE, "HOSE", LocalDate.of(2035, 1, 3)));
        succeed(hpg, 0);
        succeed(hpg, 1);
        var selected = selection(hpg, 2);
        assertThat(store.findScopedCandidates(selected, NOW, 10))
                .singleElement()
                .satisfies(
                        candidate -> assertThat(candidate.messageId()).isEqualTo(message(hpg, 2)));
        assertThat(claim(selected, message(hpg, 2), "hpg")).isNotNull();
        assertThat(claim(selection(fpt, 2), message(fpt, 2), "fpt")).isNull();
        var foreign =
                new Selection(
                        hpg,
                        2,
                        selected.definitionId(),
                        new Scope(ScopeType.SYMBOL, "HOSE:FPT", selected.scope().businessDate()),
                        1,
                        selected.reason());
        assertThat(store.findScopedCandidates(foreign, NOW, 10)).isEmpty();
        succeed(exchange, 0);
        succeed(exchange, 1);
        assertThat(claim(selection(exchange, 2), message(exchange, 2), "exchange")).isNotNull();
    }

    static UUID prepare(boolean join, boolean closed) {
        return prepare(join, closed, 1);
    }

    static UUID prepare(boolean join, boolean closed, int children) {
        return prepare(
                join,
                closed,
                children,
                new Scope(ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2)));
    }

    static UUID prepare(boolean join, boolean closed, int children, Scope targetScope) {
        return tx.execute(
                status -> {
                    List<RequiredPathResolver.Step> steps = new ArrayList<>();
                    for (String cron : List.of("a", "b", "target")) {
                        var key =
                                new StepKey(
                                        new JobDefinitionIdentity(
                                                DataSource.VND, JobType.SYNC_SYMBOLS, cron),
                                        steps.isEmpty()
                                                ? new Scope(
                                                        ScopeType.GLOBAL,
                                                        "VN",
                                                        targetScope.businessDate())
                                                : targetScope);
                        var predecessors =
                                steps.isEmpty() || join && steps.size() == 1
                                        ? List.<StepKey>of()
                                        : join
                                                ? List.of(steps.get(0).key(), steps.get(1).key())
                                                : List.of(steps.getLast().key());
                        steps.add(
                                new RequiredPathResolver.Step(
                                        key,
                                        TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS),
                                        predecessors));
                    }
                    var saved =
                            runs.prepare(
                                    new Request(
                                            Mode.MANUAL,
                                            "admission-test",
                                            UUID.randomUUID().toString(),
                                            "contract fixture",
                                            null,
                                            "{}"),
                                    new RequiredPathResolver.RequiredPath(
                                            1, steps.getLast().key(), steps));
                    if (!closed) return saved.id();
                    for (int index = 0; index < 3; index++) {
                        UUID parent = UUID.randomUUID();
                        var json =
                                context.getBean(
                                        "jsonMapper", tools.jackson.databind.json.JsonMapper.class);
                        String metadata =
                                json.writeValueAsString(
                                        Map.of(
                                                "runPreparationVersion",
                                                "1",
                                                "businessDate",
                                                targetScope.businessDate().toString(),
                                                "workType",
                                                "GLOBAL",
                                                "workKey",
                                                "VN"));
                        jdbc.update(
                                "INSERT INTO job_execution_histories(id,job_id,used_source,status,parent_log_id,job_run_id,job_run_step_index,meta_json) VALUES (?,?,'VND','RUNNING',?,?,?,CAST(? AS jsonb))",
                                parent,
                                saved.definitionIds().get(index),
                                null,
                                saved.id(),
                                index,
                                metadata);
                        for (int childIndex = 0; childIndex < children; childIndex++) {
                            UUID child = UUID.randomUUID();
                            String childKey = "work-" + childIndex;
                            jdbc.update(
                                    "INSERT INTO job_execution_histories(id,job_id,used_source,status,parent_log_id,job_run_id,job_run_step_index,meta_json) VALUES (?,?,'VND','RUNNING',?,?,?,CAST(? AS jsonb))",
                                    child,
                                    saved.definitionIds().get(index),
                                    parent,
                                    saved.id(),
                                    index,
                                    metadata.replace("\"VN\"", "\"" + childKey + "\""));
                            String payload =
                                    json.writeValueAsString(
                                            Map.of(
                                                    "executionId",
                                                    child.toString(),
                                                    "parentExecutionId",
                                                    parent.toString(),
                                                    "workType",
                                                    "GLOBAL",
                                                    "workKey",
                                                    childKey));
                            jdbc.update(
                                    "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'test',?,?)",
                                    child,
                                    payload,
                                    java.sql.Timestamp.from(NOW));
                        }
                        jdbc.update(
                                "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) VALUES (?,?,?,?,?,?)",
                                saved.id(),
                                index,
                                parent,
                                children,
                                children,
                                java.sql.Timestamp.from(NOW));
                    }
                    return saved.id();
                });
    }
}
