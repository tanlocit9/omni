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
                "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'test','{}',?)",
                legacy,
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

    static UUID prepare(boolean join, boolean closed) {
        return tx.execute(
                status -> {
                    List<RequiredPathResolver.Step> steps = new ArrayList<>();
                    for (String cron : List.of("a", "b", "target")) {
                        var key =
                                new StepKey(
                                        new JobDefinitionIdentity(
                                                DataSource.VND, JobType.SYNC_SYMBOLS, cron),
                                        new Scope(
                                                ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2)));
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
                        UUID parent = UUID.randomUUID(), child = UUID.randomUUID();
                        var json =
                                context.getBean(
                                        "jsonMapper", tools.jackson.databind.json.JsonMapper.class);
                        String metadata =
                                "{\"runPreparationVersion\":\"1\",\"businessDate\":\"2035-01-02\",\"workType\":\"GLOBAL\",\"workKey\":\"VN\"}";
                        for (UUID id : List.of(parent, child))
                            jdbc.update(
                                    "INSERT INTO job_execution_histories(id,job_id,used_source,status,parent_log_id,job_run_id,job_run_step_index,meta_json) VALUES (?,?,'VND','RUNNING',?,?,?,CAST(? AS jsonb))",
                                    id,
                                    saved.definitionIds().get(index),
                                    id.equals(parent) ? null : parent,
                                    saved.id(),
                                    index,
                                    metadata);
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
                                                "VN"));
                        jdbc.update(
                                "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'test',?,?)",
                                child,
                                payload,
                                java.sql.Timestamp.from(NOW));
                        jdbc.update(
                                "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) VALUES (?,?,?,1,1,?)",
                                saved.id(),
                                index,
                                parent,
                                java.sql.Timestamp.from(NOW));
                    }
                    return saved.id();
                });
    }
}
