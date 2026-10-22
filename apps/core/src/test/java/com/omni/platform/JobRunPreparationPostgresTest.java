package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunPreparationService;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.executions.WorkType;

/** Actual JPA/JDBC transactions and real producers, with Kafka/schedulers/object storage isolated. */
@Testcontainers
@Timeout(120)
class JobRunPreparationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z"); // Tuesday in Vietnam.
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JobDefinitionRepository definitions;
    static JobRunPreparationService preparation;
    static SchedulerClaimService claims;

    @BeforeAll
    static void start() {
        context = PlatformStartupPostgresTest.startApplication(POSTGRES, true);
        jdbc = context.getBean(JdbcTemplate.class);
        definitions = context.getBean(JobDefinitionRepository.class);
        preparation = context.getBean(JobRunPreparationService.class);
        claims = context.getBean(SchedulerClaimService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE job_runs, job_execution_histories, symbols CASCADE");
        jdbc.update(
                "UPDATE job_definitions SET is_active=true, claim_token=null, claimed_by=null, claimed_at=null, claim_until=null");
        // Reapply exactly the seed configuration; retain IDs and the test's independent runtime
        // cadence.
        var json = context.getBean("jsonMapper", tools.jackson.databind.json.JsonMapper.class);
        for (var seed :
                com.omni.platform.modules.scheduler.constants.JobDefinitionConfig
                        .JOB_DEFINITION_SEEDS) {
            jdbc.update(
                    "UPDATE job_definitions SET config_json=CAST(? AS jsonb) WHERE source=? AND job_type=? AND cron_expr=?",
                    json.writeValueAsString(seed.config()),
                    seed.source().name(),
                    seed.jobType().name(),
                    seed.cronExpr());
        }
        String sector =
                String.valueOf(
                        ((List<?>)
                                        target(JobType.SYNC_STOCK_PRICE)
                                                .getConfigJson()
                                                .get("sectorCodes"))
                                .getFirst());
        jdbc.update(
                "INSERT INTO symbols(code,exchange,meta_json) VALUES ('AAA','HOSE',CAST(? AS jsonb))",
                json.writeValueAsString(Map.of("sectorLv1Code", sector, "sectorLv2Code", sector)));
    }

    @Test
    void preparesDependenciesBeforeTargetWithFrozenExecutionAndMessageMembership() {
        var target = target(JobType.SYNC_INDICATORS);
        UUID execution = preparation.prepareScheduled(target, claim(target), NOW);
        assertThat(count("job_runs")).isEqualTo(1);
        int steps = count("job_run_steps");
        assertThat(steps)
                .isGreaterThan(
                        3); // Every declared sector-specific stock-price definition is retained.
        assertThat(count("job_run_step_preparations")).isEqualTo(steps);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT job_run_step_index FROM job_execution_histories WHERE id=?",
                                Integer.class,
                                execution))
                .isEqualTo(steps - 1);
        assertThat(
                        jdbc.queryForList(
                                "SELECT h.job_run_step_index FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id ORDER BY o.created_at, o.id",
                                Integer.class))
                .hasSize(5)
                .isSorted()
                .startsWith(0, 0, 0)
                .endsWith(steps - 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE job_run_id IS NULL",
                                Integer.class))
                .isZero();
        assertThat(definitions.findById(target.getId()).orElseThrow().getNextRun()).isAfter(NOW);
        assertThat(definitions.findById(target.getId()).orElseThrow().getClaimToken()).isNull();
    }

    @Test
    void materializesAllJoinBranchesAndSignalsWithoutDroppingNotYetReadyPartitions() {
        var target = target(JobType.EVALUATE_SIGNALS);
        preparation.prepareScheduled(target, claim(target), NOW);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_run_steps WHERE definition_job_type='SYNC_SIGNALS'",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories h JOIN job_definitions d ON h.job_id=d.id WHERE d.job_type='SYNC_SIGNALS' AND parent_log_id IS NOT NULL",
                                Integer.class))
                .isEqualTo(3);
        assertThat(count("job_run_steps")).isEqualTo(count("job_run_step_preparations"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_run_step_predecessors p LEFT JOIN job_run_step_preparations s ON s.job_run_id=p.job_run_id AND s.step_index=p.predecessor_index WHERE s.parent_execution_id IS NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void concurrentRetriesAndFreshInstancesReuseTheOriginalClosedPath() throws Exception {
        var target = target(JobType.SYNC_INDICATORS);
        var lease = claim(target);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> preparation.prepareScheduled(target, lease, NOW)));
            UUID first = futures.getFirst().get(40, TimeUnit.SECONDS);
            for (var future : futures)
                assertThat(future.get(40, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(count("job_runs")).isEqualTo(1);
            assertThat(count("scheduler_outbox_messages")).isEqualTo(5);
            // A retry consults persisted closure, not the current source/config or mutable symbol
            // catalog.
            jdbc.update("UPDATE job_definitions SET config_json='{}' WHERE id=?", target.getId());
            jdbc.update("INSERT INTO symbols(code,exchange) VALUES ('BBB','HOSE')");
            assertThat(preparation.prepareScheduled(target, lease, NOW.plusSeconds(86400)))
                    .isEqualTo(first);
            assertThat(count("scheduler_outbox_messages")).isEqualTo(5);
        }
    }

    @Test
    void rollsBackTheWholePathAndPreservesTheLeaseForRetry() {
        var target = target(JobType.SYNC_INDICATORS);
        var lease = claim(target);
        jdbc.update("UPDATE job_definitions SET is_active=false WHERE job_type='SYNC_STOCK_PRICE'");
        assertThatThrownBy(() -> preparation.prepareScheduled(target, lease, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertEmptyIntent();
        assertThat(definitions.findById(target.getId()).orElseThrow().getClaimToken())
                .isEqualTo(lease.claimToken());
        jdbc.update("UPDATE job_definitions SET is_active=true WHERE job_type='SYNC_STOCK_PRICE'");
        assertThat(preparation.prepareScheduled(target, lease, NOW)).isNotNull();
    }

    @Test
    void outerTransactionRollbackLeavesNoPartialPreparationOrCadenceChange() {
        var target = target(JobType.SYNC_INDICATORS);
        var lease = claim(target);
        Instant next = definitions.findById(target.getId()).orElseThrow().getNextRun();
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.executeWithoutResult(
                status -> {
                    preparation.prepareScheduled(target, lease, NOW);
                    status.setRollbackOnly();
                });
        assertEmptyIntent();
        assertThat(definitions.findById(target.getId()).orElseThrow().getNextRun()).isEqualTo(next);
        assertThat(preparation.prepareScheduled(target, lease, NOW)).isNotNull();
    }

    @Test
    void rejectsOversizedAndDuplicateFanoutWithoutCommittingPartialRows() {
        jdbc.update(
                "INSERT INTO symbols(code,exchange,meta_json) SELECT 'X'||i,'HOSE',s.meta_json FROM generate_series(1,5001) i CROSS JOIN (SELECT meta_json FROM symbols LIMIT 1) s");
        var target = target(JobType.SYNC_STOCK_PRICE);
        var lease = claim(target);
        assertThatThrownBy(() -> preparation.prepareScheduled(target, lease, NOW))
                .hasMessageContaining("WORK_BOUND");
        assertEmptyIntent();
        jdbc.execute("TRUNCATE symbols");
        var symbols = target(JobType.SYNC_SYMBOLS);
        jdbc.update(
                "UPDATE job_definitions SET config_json='{\"exchanges\":[\"HOSE\",\"HOSE\"]}' WHERE id=?",
                symbols.getId());
        var symbolsLease = claim(symbols);
        assertThatThrownBy(() -> preparation.prepareScheduled(symbols, symbolsLease, NOW))
                .hasMessageContaining("DUPLICATE_WORK");
        assertEmptyIntent();
    }

    @Test
    void closesZeroChildAndDirectRootMessageMembershipAndPreservesManualCadence() {
        var target = target(JobType.SYNC_INDICATORS);
        jdbc.execute("TRUNCATE symbols");
        UUID noOp = preparation.prepareScheduled(target, claim(target), NOW);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE job_id=? AND parent_log_id IS NULL",
                                String.class,
                                target.getId()))
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT child_count FROM job_run_step_preparations WHERE parent_execution_id=?",
                                Integer.class,
                                noOp))
                .isZero();
        var metadata = target(JobType.SYNC_METADATA);
        var next = metadata.getNextRun();
        UUID id =
                preparation.prepareManual(
                        metadata,
                        claim(metadata),
                        NOW,
                        "operator",
                        "metadata-key",
                        "Test metadata",
                        Map.of("dataset", "eod"),
                        Map.of("metadataTarget", Map.of("dataset", "eod")));
        assertThat(definitions.findById(metadata.getId()).orElseThrow().getNextRun())
                .isEqualTo(next);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT message_count FROM job_run_step_preparations WHERE parent_execution_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM scheduler_outbox_messages WHERE execution_id=?",
                                String.class,
                                id))
                .contains("eod");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_regclass('public.manual_job_triggers') IS NULL",
                                Boolean.class))
                .isTrue(); // Internal MANUAL preparation remains tested; operator triggering is
        // deferred.
        assertThatThrownBy(
                        () ->
                                preparation.prepareManual(
                                        metadata,
                                        claim(metadata),
                                        NOW,
                                        "operator",
                                        "metadata-key",
                                        "changed reason",
                                        Map.of(),
                                        Map.of()))
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void sealedMembershipRejectsAppendDeleteAndIdentityMutationButAllowsProgress() {
        var target = target(JobType.SYNC_INDICATORS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        for (String sql :
                List.of(
                        "DELETE FROM job_run_step_preparations WHERE parent_execution_id=?",
                        "UPDATE job_run_step_preparations SET child_count=0 WHERE parent_execution_id=?",
                        "DELETE FROM job_execution_histories WHERE id=?",
                        "UPDATE job_execution_histories SET meta_json=jsonb_set(meta_json,'{workKey}','\"changed\"') WHERE id=?",
                        "DELETE FROM scheduler_outbox_messages WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE parent_log_id=?)",
                        "UPDATE scheduler_outbox_messages SET payload='{}' WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE parent_log_id=?)")) {
            assertThatThrownBy(() -> jdbc.update(sql, root))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO job_execution_histories (job_id,used_source,status,parent_log_id,job_run_id,job_run_step_index) SELECT job_id,used_source,'RUNNING',id,job_run_id,job_run_step_index FROM job_execution_histories WHERE id=?",
                                        root))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO scheduler_outbox_messages (execution_id,message_index,topic,payload) SELECT id,99,'test','{}' FROM job_execution_histories WHERE parent_log_id=?",
                                        root))
                .isInstanceOf(DataAccessException.class);
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE parent_log_id=?", root);
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET available_at=now(), dependency_reason='WAITING' WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE parent_log_id=?)",
                root);
    }

    @Test
    void rejectsStaleAndWrongTargetClaimsAndWeekendIntradayPreparation() {
        var target = target(JobType.SYNC_INDICATORS);
        var lease = claim(target);
        var stale =
                new SchedulerClaim(
                        target.getId(),
                        UUID.randomUUID(),
                        lease.claimedBy(),
                        NOW,
                        NOW.plusSeconds(300),
                        NOW);
        assertThatThrownBy(() -> preparation.prepareScheduled(target, stale, NOW))
                .hasMessageContaining("claim is stale");
        assertEmptyIntent();
        assertThatThrownBy(
                        () ->
                                preparation.prepareScheduled(
                                        target(JobType.SYNC_METADATA), lease, NOW))
                .hasMessageContaining("target mismatch");
        var intraday = target(JobType.SYNC_INTRADAY_EOD);
        var intradayLease =
                claims.claimJobDefinition(intraday.getId(), NOW.plusSeconds(5 * 86400), "test")
                        .orElseThrow();
        assertThatThrownBy(
                        () ->
                                preparation.prepareScheduled(
                                        intraday, intradayLease, NOW.plusSeconds(5 * 86400)))
                .hasMessageContaining("weekday");
        assertEmptyIntent();
    }

    @Test
    void preparesEveryCanonicalProducerUsingBoundedCatalogReadsAndExistingWirePayloads() {
        var sectorTarget = target(JobType.PRECOMPUTE_SECTOR_FEATURES);
        preparation.prepareScheduled(sectorTarget, claim(sectorTarget), NOW);
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"sectorCodes\":[],\"focusSectorCodes\":[]}'");
        for (var definition : definitions.findAll()) {
            UUID execution = preparation.prepareScheduled(definition, claim(definition), NOW);
            assertThat(execution).isNotNull();
        }
        assertThat(count("job_runs")).isEqualTo(definitions.count());
        assertThat(count("job_run_step_preparations")).isEqualTo(count("job_run_steps"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages WHERE payload LIKE '%jobRunId%'",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE meta_json->>'workType'='SECTOR'",
                                Integer.class))
                .isPositive();
    }

    @Test
    void readsSymbolOffsetsInOneBoundedLookupAndRetainsCanonicalWorkIdentity() {
        var target = target(JobType.SYNC_STOCK_PRICE);
        jdbc.update(
                "INSERT INTO job_execution_histories(job_id, used_source, status, new_offset, finished_at, meta_json) VALUES (?,?,'SUCCESS','2030-01-01',now(),'{\"workType\":\"SYMBOL\",\"workKey\":\"HOSE-AAA\"}')",
                target.getId(),
                target.getSource().name());
        preparation.prepareScheduled(target, claim(target), NOW);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT o.payload FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_id=?",
                                String.class,
                                target.getId()))
                .contains("2030-01-01T00:00:00Z");
    }

    @Test
    void databaseRejectsUnclosedPartialPathsAndIncorrectClosureCounts() {
        var target = target(JobType.SYNC_METADATA);
        UUID original = preparation.prepareScheduled(target, claim(target), NOW);
        UUID run =
                jdbc.queryForObject(
                        "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        original);
        var snapshot =
                context.getBean(
                                com.omni.platform.modules.scheduler.repositories.JobRunRepository
                                        .class)
                        .findById(run)
                        .orElseThrow();
        var next =
                context.getBean(
                                com.omni.platform.modules.scheduler.repositories.JobRunRepository
                                        .class)
                        .prepare(
                                new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                                        com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode
                                                .MANUAL,
                                        "sql-test",
                                        "unclosed",
                                        "Verify closure",
                                        null,
                                        "{}"),
                                snapshot.path().requiredPath());
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            UUID root = UUID.randomUUID();
                                            jdbc.update(
                                                    "INSERT INTO job_execution_histories (id, job_id,used_source,status,job_run_id,job_run_step_index,meta_json) VALUES (?,?,?,'PENDING',?,0,'{\"runPreparationVersion\":\"1\",\"workType\":\"GLOBAL\",\"workKey\":\"SQL_TEST\",\"businessDate\":\"2035-01-02\"}')",
                                                    root,
                                                    target.getId(),
                                                    target.getSource().name(),
                                                    next.id());
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            UUID root = UUID.randomUUID();
                                            jdbc.update(
                                                    "INSERT INTO job_execution_histories (id, job_id,used_source,status,job_run_id,job_run_step_index,meta_json) VALUES (?,?,?,'PENDING',?,0,'{\"runPreparationVersion\":\"1\",\"workType\":\"GLOBAL\",\"workKey\":\"SQL_TEST\",\"businessDate\":\"2035-01-02\"}')",
                                                    root,
                                                    target.getId(),
                                                    target.getSource().name(),
                                                    next.id());
                                            jdbc.update(
                                                    "INSERT INTO job_run_step_preparations VALUES (?,0,?,1,1,now())",
                                                    next.id(),
                                                    root);
                                        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Incomplete JobRun");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE job_run_id=?",
                                Integer.class,
                                next.id()))
                .isZero();
    }

    @Test
    void scheduledAndManualReplayLockDefinitionBeforeRun() throws Exception {
        for (boolean manual : List.of(false, true)) {
            var target = target(JobType.SYNC_METADATA);
            var originalClaim = claim(target);
            UUID original =
                    manual
                            ? preparation.prepareManual(
                                    target,
                                    originalClaim,
                                    NOW,
                                    "operator",
                                    "lock-order",
                                    "Replay lock order",
                                    Map.of(),
                                    Map.of())
                            : preparation.prepareScheduled(target, originalClaim, NOW);
            var fresh = claim(target);
            java.util.function.Supplier<UUID> replay =
                    () ->
                            manual
                                    ? preparation.prepareManual(
                                            target,
                                            fresh,
                                            NOW,
                                            "operator",
                                            "lock-order",
                                            "Replay lock order",
                                            Map.of(),
                                            Map.of())
                                    : preparation.prepareScheduled(target, fresh, NOW);
            var nextRun = definitions.findById(target.getId()).orElseThrow().getNextRun();
            int runCount = count("job_runs");
            int executionCount = count("job_execution_histories");
            int messageCount = count("scheduler_outbox_messages");
            var definitionLocked = new CountDownLatch(1);
            var replayStarted = new CountDownLatch(1);
            var replayPid = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                // A preparation that missed the original uncommitted run reaches this
                // same state: definition locked, then it discovers the committed run.
                var definitionFirst =
                        pool.submit(
                                () ->
                                        transaction()
                                                .execute(
                                                        status -> {
                                                            setFiniteLockWaits();
                                                            int holderPid =
                                                                    jdbc.queryForObject(
                                                                            "SELECT pg_backend_pid()",
                                                                            Integer.class);
                                                            jdbc.queryForObject(
                                                                    "SELECT id FROM job_definitions WHERE id=? FOR UPDATE",
                                                                    UUID.class,
                                                                    target.getId());
                                                            definitionLocked.countDown();
                                                            awaitLatch(replayStarted);
                                                            awaitLockWait(
                                                                    replayPid.get(), holderPid);
                                                            return replay.get();
                                                        }));
                try {
                    awaitLatch(definitionLocked);
                    var concurrentReplay =
                            pool.submit(
                                    () ->
                                            transaction()
                                                    .execute(
                                                            status -> {
                                                                setFiniteLockWaits();
                                                                replayPid.set(
                                                                        jdbc.queryForObject(
                                                                                "SELECT pg_backend_pid()",
                                                                                Integer.class));
                                                                replayStarted.countDown();
                                                                return replay.get();
                                                            }));
                    try {
                        assertThat(definitionFirst.get(12, TimeUnit.SECONDS)).isEqualTo(original);
                        assertThat(concurrentReplay.get(12, TimeUnit.SECONDS)).isEqualTo(original);
                    } finally {
                        concurrentReplay.cancel(true);
                    }
                } finally {
                    replayStarted.countDown();
                    definitionFirst.cancel(true);
                }
            }
            assertThat(count("job_runs")).isEqualTo(runCount);
            assertThat(count("job_execution_histories")).isEqualTo(executionCount);
            assertThat(count("scheduler_outbox_messages")).isEqualTo(messageCount);
            var after = definitions.findById(target.getId()).orElseThrow();
            assertThat(after.getClaimToken()).isNull();
            assertThat(after.getNextRun()).isEqualTo(nextRun);
        }
    }

    @Test
    void retriesReleaseOnlyTheirOwnFreshLeaseAndRejectCrossTargetIdempotencyReuse() {
        var target = target(JobType.SYNC_METADATA);
        UUID first = preparation.prepareScheduled(target, claim(target), NOW);
        var fresh = claim(target);
        var stale =
                new SchedulerClaim(
                        target.getId(),
                        UUID.randomUUID(),
                        fresh.claimedBy(),
                        NOW,
                        fresh.claimUntil(),
                        fresh.scheduledFor());
        assertThatThrownBy(() -> preparation.prepareScheduled(target, stale, NOW))
                .hasMessageContaining("retry claim is stale");
        assertThat(preparation.prepareScheduled(target, fresh, NOW)).isEqualTo(first);
        assertThat(definitions.findById(target.getId()).orElseThrow().getClaimToken()).isNull();
        preparation.prepareManual(
                target,
                claim(target),
                NOW,
                "operator",
                "same-target",
                "Test retry",
                Map.of(),
                Map.of());
        var other = target(JobType.SYNC_SYMBOLS);
        var otherLease = claim(other);
        assertThatThrownBy(
                        () ->
                                preparation.prepareManual(
                                        other,
                                        otherLease,
                                        NOW,
                                        "operator",
                                        "same-target",
                                        "Test retry",
                                        Map.of(),
                                        Map.of()))
                .hasMessageContaining("target changed");
        assertThat(count("job_runs")).isEqualTo(2);
    }

    @Test
    void rejectsExpiredOrOwnerMismatchedLeasesAndDoesNotTreatAnUnpreparedSnapshotAsClosed() {
        var target = target(JobType.SYNC_METADATA);
        var lease = claim(target);
        var wrongOwner =
                new SchedulerClaim(
                        target.getId(),
                        lease.claimToken(),
                        "other-owner",
                        NOW,
                        lease.claimUntil(),
                        lease.scheduledFor());
        assertThatThrownBy(() -> preparation.prepareScheduled(target, wrongOwner, NOW))
                .hasMessageContaining("claim is stale");
        assertThatThrownBy(() -> preparation.prepareScheduled(target, lease, lease.claimUntil()))
                .hasMessageContaining("claim is stale");
        assertEmptyIntent();
        UUID prepared = preparation.prepareScheduled(target, lease, NOW);
        UUID run =
                jdbc.queryForObject(
                        "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        prepared);
        var repository =
                context.getBean(
                        com.omni.platform.modules.scheduler.repositories.JobRunRepository.class);
        var path = repository.findById(run).orElseThrow().path().requiredPath();
        Instant earlier = NOW.minusSeconds(86400);
        repository.prepare(
                new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                        com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.SCHEDULED,
                        "scheduler",
                        target.getId() + ":" + earlier.toEpochMilli(),
                        "Scheduled target",
                        null,
                        "{}"),
                path);
        var retry = claim(target);
        var earlierLease =
                new SchedulerClaim(
                        target.getId(),
                        retry.claimToken(),
                        retry.claimedBy(),
                        NOW,
                        retry.claimUntil(),
                        earlier);
        assertThatThrownBy(() -> preparation.prepareScheduled(target, earlierLease, NOW))
                .hasMessageContaining("INCOMPLETE_PREPARATION");
    }

    @Test
    void databaseRejectsMovingLegacyRowsIntoClosedMembershipAndCommittingOnlyPartOfAPath() {
        var target = target(JobType.SYNC_INDICATORS);
        UUID parent = preparation.prepareScheduled(target, claim(target), NOW);
        UUID child =
                jdbc.queryForObject(
                        "SELECT id FROM job_execution_histories WHERE parent_log_id=?",
                        UUID.class,
                        parent);
        UUID legacy =
                jdbc.queryForObject(
                        "INSERT INTO job_execution_histories(job_id,used_source,status) VALUES (?,?,'PENDING') RETURNING id",
                        UUID.class,
                        target.getId(),
                        target.getSource().name());
        UUID outbox =
                jdbc.queryForObject(
                        "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload) VALUES (?,0,'legacy','{}') RETURNING id",
                        UUID.class,
                        legacy);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE job_execution_histories SET parent_log_id=?,job_run_id=(SELECT job_run_id FROM job_execution_histories WHERE id=?),job_run_step_index=(SELECT job_run_step_index FROM job_execution_histories WHERE id=?) WHERE id=?",
                                        parent,
                                        parent,
                                        parent,
                                        legacy))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("closed JobRun");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE scheduler_outbox_messages SET execution_id=? WHERE id=?",
                                        child,
                                        outbox))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("closed JobRun");
        UUID runId =
                jdbc.queryForObject(
                        "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        parent);
        var repository =
                context.getBean(
                        com.omni.platform.modules.scheduler.repositories.JobRunRepository.class);
        var original = repository.findById(runId).orElseThrow();
        var partial =
                repository.prepare(
                        new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                                com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.MANUAL,
                                "sql-test",
                                "partial-path",
                                "Verify whole path closure",
                                null,
                                "{}"),
                        original.path().requiredPath());
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            UUID first = UUID.randomUUID();
                                            jdbc.update(
                                                    "INSERT INTO job_execution_histories(id,job_id,used_source,status,job_run_id,job_run_step_index,meta_json) VALUES (?,?,?,'SUCCESS',?,0,'{\"runPreparationVersion\":\"1\",\"workType\":\"GLOBAL\",\"workKey\":\"SQL_TEST\",\"businessDate\":\"2035-01-02\"}')",
                                                    first,
                                                    partial.definitionIds().getFirst(),
                                                    original.path()
                                                            .requiredPath()
                                                            .steps()
                                                            .getFirst()
                                                            .key()
                                                            .definition()
                                                            .source()
                                                            .name(),
                                                    partial.id());
                                            jdbc.update(
                                                    "INSERT INTO job_run_step_preparations VALUES (?,0,?,0,0,now())",
                                                    partial.id(),
                                                    first);
                                        }))
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("must close before commit");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_run_step_preparations WHERE job_run_id=?",
                                Integer.class,
                                partial.id()))
                .isZero();
    }

    @Test
    void reloadsTheFencingTokenUnderLockInsteadOfOverwritingCachedJpaClaimState() {
        var target = target(JobType.SYNC_METADATA);
        var lease = claim(target);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            definitions.findById(target.getId()).orElseThrow();
                                            jdbc.update(
                                                    "UPDATE job_definitions SET claim_token=? WHERE id=?",
                                                    UUID.randomUUID(),
                                                    target.getId());
                                            preparation.prepareScheduled(target, lease, NOW);
                                        }))
                .hasMessageContaining("claim is stale");
        assertEmptyIntent();
        assertThat(definitions.findById(target.getId()).orElseThrow().getClaimToken())
                .isEqualTo(lease.claimToken());
    }

    @Test
    void existingProducerRoutesStillCreateLegacyIntentWithoutJobRunLinks() {
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"sectorCodes\":[],\"focusSectorCodes\":[]}'");
        var producers =
                context.getBean(
                        com.omni.platform.modules.scheduler.producers.JobProducerRegistry.class);
        for (var definition : definitions.findAll()) {
            // Signal manifest deferral has its own existing unit tests; avoid any object-store
            // call.
            if (definition.getJobType() == JobType.SYNC_SIGNALS) continue;
            var lease = claim(definition);
            var claimed = definitions.findById(definition.getId()).orElseThrow();
            assertThat(
                            producers
                                    .getProducer(definition.getJobType())
                                    .prepareDispatch(claimed, lease, NOW))
                    .isNotNull();
        }
        assertThat(count("job_execution_histories")).isPositive();
        assertThat(count("scheduler_outbox_messages")).isPositive();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE job_run_id IS NOT NULL",
                                Integer.class))
                .isZero();
        assertThat(count("job_runs")).isZero();
        assertThat(count("job_run_step_preparations")).isZero();
    }

    @Test
    @Timeout(180)
    void enforcesTheWholeRunBudgetAcrossIndividuallyValidFanouts() {
        jdbc.update(
                "INSERT INTO symbols(code,exchange,meta_json) SELECT 'B'||i,'HOSE',s.meta_json FROM generate_series(1,1000) i CROSS JOIN (SELECT meta_json FROM symbols LIMIT 1) s");
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"sectorCodes\":[]}' WHERE job_type='SYNC_STOCK_PRICE'");
        var target = target(JobType.SYNC_INDICATORS);
        var lease = claim(target);
        // Each step is below 5000, but all sector definitions together exceed 20000 messages.
        assertThatThrownBy(() -> preparation.prepareScheduled(target, lease, NOW))
                .hasMessageContaining("WORK_BOUND");
        assertEmptyIntent();
        assertThat(definitions.findById(target.getId()).orElseThrow().getClaimToken())
                .isEqualTo(lease.claimToken());
    }

    @Test
    void preservesProducerDefaultsForEmptyAndNullableConfigurationAndZeroSignalMembership() {
        int sequence = 0;
        for (JobType type :
                List.of(
                        JobType.SYNC_INDICATORS,
                        JobType.PRECOMPUTE_SYMBOL_FEATURES,
                        JobType.SECTOR_ROTATION_BACKTEST,
                        JobType.EVALUATE_SIGNALS,
                        JobType.SYNC_METADATA,
                        JobType.SYNC_SIGNALS)) {
            var target = target(type);
            for (String config : new String[] {"{}", null}) {
                jdbc.update(
                        "UPDATE job_definitions SET config_json=CAST(? AS jsonb) WHERE id=?",
                        config,
                        target.getId());
                Instant now = NOW.plusSeconds(++sequence);
                var lease =
                        claims.claimJobDefinition(target.getId(), now, "defaults-test")
                                .orElseThrow();
                assertThat(preparation.prepareScheduled(target, lease, now)).isNotNull();
            }
        }
        jdbc.execute("TRUNCATE symbols");
        var signals = target(JobType.SYNC_SIGNALS);
        Instant now = NOW.plusSeconds(++sequence);
        var lease = claims.claimJobDefinition(signals.getId(), now, "defaults-test").orElseThrow();
        UUID root = preparation.prepareScheduled(signals, lease, now);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                root))
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT child_count FROM job_run_step_preparations WHERE parent_execution_id=?",
                                Integer.class,
                                root))
                .isZero();
    }

    @Test
    void concurrentIndependentTargetsWithSharedAncestorsKeepSeparateClosedRuns() throws Exception {
        var indicators = target(JobType.SYNC_INDICATORS);
        var sectors = target(JobType.PRECOMPUTE_SECTOR_FEATURES);
        var indicatorLease = claim(indicators);
        var sectorLease = claim(sectors);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return preparation.prepareScheduled(
                                        indicators, indicatorLease, NOW);
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return preparation.prepareScheduled(sectors, sectorLease, NOW);
                            });
            start.countDown();
            UUID first = a.get(40, TimeUnit.SECONDS);
            UUID second = b.get(40, TimeUnit.SECONDS);
            assertThat(first).isNotEqualTo(second);
        }
        assertThat(count("job_runs")).isEqualTo(2);
        assertThat(count("job_run_step_preparations")).isEqualTo(count("job_run_steps"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT job_run_id) FROM job_execution_histories",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void symbolUpsertPreservesPreparedExchangeMembershipAcrossRetries() {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        int histories = count("job_execution_histories");
        UUID exchange =
                jdbc.queryForObject(
                        "SELECT id FROM job_execution_histories WHERE parent_log_id=? AND meta_json->>'workKey'='HOSE'",
                        UUID.class,
                        root);
        for (int retry = 0; retry < 2; retry++) {
            upsertSymbols(target.getId(), exchange, root, "NEW");
        }
        assertThat(count("job_execution_histories")).isEqualTo(histories);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT child_count FROM job_run_step_preparations WHERE parent_execution_id=?",
                                Integer.class,
                                root))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM symbols WHERE code='NEW' AND exchange='HOSE' AND is_active=true",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                root))
                .isEqualTo("RUNNING");
    }

    @Test
    void symbolUpsertRetainsLegacyChildTrackingAndUntrackedDataWrites() {
        var target = target(JobType.SYNC_SYMBOLS);
        var jobs = context.getBean(com.omni.platform.modules.scheduler.services.JobService.class);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        UUID root = tx.execute(status -> jobs.prepareForExecution(target, NOW).getId());
        upsertSymbols(target.getId(), null, root, "LEGACY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE parent_log_id=? AND job_run_id IS NULL AND status='SUCCESS'",
                                Integer.class,
                                root))
                .isEqualTo(1);
        int histories = count("job_execution_histories");
        upsertSymbols(target.getId(), null, null, "UNTRACKED");
        assertThat(count("job_execution_histories")).isEqualTo(histories);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM symbols WHERE code='UNTRACKED' AND is_active=true",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsNullLinkedInsertsAndLegacyRebindingUnderPreparedParents() {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO job_execution_histories(job_id,used_source,status,parent_log_id) SELECT job_id,used_source,'RUNNING',id FROM job_execution_histories WHERE id=?",
                                        root))
                .isInstanceOf(DataAccessException.class);
        var jobs = context.getBean(com.omni.platform.modules.scheduler.services.JobService.class);
        UUID legacy =
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                        .execute(status -> jobs.prepareForExecution(target, NOW).getId());
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE job_execution_histories SET parent_log_id=? WHERE id=?",
                                        root,
                                        legacy))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE parent_log_id=?",
                                Integer.class,
                                root))
                .isEqualTo(3);
    }

    @Test
    void executionProgressCommitsWhileAnotherTransactionHoldsTheRunLock() throws Exception {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        UUID child = hoseExecution(root);
        UUID run = runFor(root);
        UUID legacy =
                jdbc.queryForObject(
                        "INSERT INTO job_execution_histories(job_id,used_source,status,meta_json) SELECT id,source,'PENDING',NULL FROM job_definitions WHERE id=? RETURNING id",
                        UUID.class,
                        target.getId());
        withRunLocked(
                run,
                () -> {
                    assertThat(
                                    jdbc.update(
                                            "UPDATE job_execution_histories SET status='RUNNING',records_synced=7,new_offset='offset',error='progress',meta_json=meta_json || '{\"recordsProcessed\":7}'::jsonb WHERE id IN (?,?)",
                                            root,
                                            child))
                            .isEqualTo(2);
                    assertThat(
                                    jdbc.update(
                                            "UPDATE job_execution_histories SET status='SUCCESS',meta_json='{\"progress\":1}'::jsonb WHERE id=?",
                                            legacy))
                            .isEqualTo(1);
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT records_synced FROM job_execution_histories WHERE id=?",
                                Integer.class,
                                child))
                .isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT meta_json->>'progress' FROM job_execution_histories WHERE id=?",
                                String.class,
                                legacy))
                .isEqualTo("1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT child_count FROM job_run_step_preparations WHERE parent_execution_id=?",
                                Integer.class,
                                root))
                .isEqualTo(3);
    }

    @Test
    void outboxProgressAndFencingWorkWhileAnotherTransactionHoldsTheRunLock() throws Exception {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        UUID message =
                jdbc.queryForObject(
                        "SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",
                        UUID.class,
                        hoseExecution(root));
        var outbox = context.getBean(SchedulerOutboxService.class);
        withRunLocked(
                runFor(root),
                () -> {
                    assertThat(
                                    outbox.markWaiting(
                                            message, NOW.plusSeconds(1), "dependency pending"))
                            .isTrue();
                    var first =
                            outbox.claimEligible(
                                    message,
                                    NOW.plusSeconds(2),
                                    "lock-test",
                                    Duration.ofMinutes(2),
                                    Map.of(
                                            DatasetRef.of(
                                                    "eod",
                                                    Map.of("exchange", "HOSE", "code", "AAA")),
                                            "input-v1"));
                    assertThat(first).isNotNull();
                    assertThat(outbox.scheduleRetry(first, NOW.plusSeconds(3), "publish failed"))
                            .isTrue();
                    var second =
                            outbox.claimEligible(
                                    message,
                                    NOW.plusSeconds(4),
                                    "lock-test",
                                    Duration.ofMinutes(2),
                                    Map.of());
                    assertThat(second).isNotNull();
                    assertThat(outbox.markDelivered(first, NOW.plusSeconds(5))).isFalse();
                    assertThat(outbox.markDelivered(second, NOW.plusSeconds(5))).isTrue();
                    assertThat(outbox.markWaiting(message, NOW.plusSeconds(6), "late wait"))
                            .isFalse();
                    assertThat(
                                    outbox.claimEligible(
                                            message,
                                            NOW.plusSeconds(6),
                                            "lock-test",
                                            Duration.ofMinutes(2),
                                            Map.of()))
                            .isNull();
                    assertThat(
                                    outbox.claimEligible(
                                            UUID.randomUUID(),
                                            NOW,
                                            "lock-test",
                                            Duration.ofMinutes(2),
                                            Map.of()))
                            .isNull();
                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scheduler_outbox_messages WHERE id=?",
                                String.class,
                                message))
                .isEqualTo("PUBLISHED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM scheduler_outbox_messages WHERE id=?",
                                Integer.class,
                                message))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT meta_json->'approvedInputs'->0->>'dataVersion' FROM job_execution_histories WHERE id=?",
                                String.class,
                                hoseExecution(root)))
                .isEqualTo("input-v1");
    }

    @Test
    void statusAndSymbolUpsertCommitWhenTheParentIsLockedBeforeTheChildFlush() throws Exception {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        UUID child = hoseExecution(root);
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS',finished_at=? WHERE parent_log_id=? AND id<>?",
                java.sql.Timestamp.from(NOW.plusSeconds(1)),
                root,
                child);
        var jobs = context.getBean(JobService.class);
        var response =
                new JobStatusMessage(
                        target.getId().toString(),
                        child.toString(),
                        root.toString(),
                        WorkType.EXCHANGE,
                        "HOSE",
                        "SUCCESS",
                        Map.of(),
                        null,
                        NOW,
                        NOW.plusSeconds(1),
                        1000,
                        null,
                        1);
        var parentLocked = new CountDownLatch(1);
        var statusStarted = new CountDownLatch(1);
        var statusPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var upsert =
                    pool.submit(
                            () ->
                                    transaction()
                                            .executeWithoutResult(
                                                    status -> {
                                                        setFiniteLockWaits();
                                                        int parentPid =
                                                                jdbc.queryForObject(
                                                                        "SELECT pg_backend_pid()",
                                                                        Integer.class);
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM job_execution_histories WHERE id=? FOR UPDATE",
                                                                UUID.class,
                                                                root);
                                                        parentLocked.countDown();
                                                        awaitLatch(statusStarted);
                                                        awaitLockWait(statusPid.get(), parentPid);
                                                        upsertSymbols(
                                                                target.getId(),
                                                                child,
                                                                root,
                                                                "LOCKSAFE");
                                                    }));
            try {
                awaitLatch(parentLocked);
                var completion =
                        pool.submit(
                                () ->
                                        transaction()
                                                .executeWithoutResult(
                                                        status -> {
                                                            setFiniteLockWaits();
                                                            statusPid.set(
                                                                    jdbc.queryForObject(
                                                                            "SELECT pg_backend_pid()",
                                                                            Integer.class));
                                                            statusStarted.countDown();
                                                            jobs.applyStatus(response);
                                                        }));
                try {
                    upsert.get(12, TimeUnit.SECONDS);
                    completion.get(12, TimeUnit.SECONDS);
                } finally {
                    completion.cancel(true);
                }
            } finally {
                statusStarted.countDown();
                upsert.cancel(true);
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                root))
                .isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT meta_json->>'successCount' FROM job_execution_histories WHERE id=?",
                                String.class,
                                root))
                .isEqualTo("3");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT records_synced FROM job_execution_histories WHERE id=?",
                                Integer.class,
                                root))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM symbols WHERE code='LOCKSAFE' AND is_active=true",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE parent_log_id=?",
                                Integer.class,
                                root))
                .isEqualTo(3);
        int notifications = count("notification_outbox_messages");
        jobs.applyStatus(response);
        assertThat(count("notification_outbox_messages")).isEqualTo(notifications);
    }

    @Test
    void progressFastPathsDoNotAllowMixedIdentityOrIntentMutation() {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        UUID child = hoseExecution(root);
        for (String identity :
                List.of("workType", "workKey", "businessDate", "runPreparationVersion")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE job_execution_histories SET status='SUCCESS',meta_json=jsonb_set(meta_json,ARRAY[?]::text[],'\"changed\"'::jsonb) WHERE id=?",
                                            identity,
                                            child))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("immutable");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                child))
                .isEqualTo("RUNNING");
        UUID message =
                jdbc.queryForObject(
                        "SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",
                        UUID.class,
                        child);
        for (String assignment :
                List.of(
                        "payload='{}'",
                        "topic='changed'",
                        "message_key='changed'",
                        "message_index=99")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE scheduler_outbox_messages SET status='PUBLISHED',"
                                                    + assignment
                                                    + " WHERE id=?",
                                            message))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("immutable");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scheduler_outbox_messages WHERE id=?",
                                String.class,
                                message))
                .isEqualTo("PENDING");
    }

    @Test
    void progressGuardDoesNotBypassStatusWorkIdentityValidation() {
        var target = target(JobType.SYNC_SYMBOLS);
        UUID root = preparation.prepareScheduled(target, claim(target), NOW);
        UUID child = hoseExecution(root);
        String parentStatus =
                jdbc.queryForObject(
                        "SELECT status FROM job_execution_histories WHERE id=?",
                        String.class,
                        root);
        record InvalidIdentity(WorkType type, String key) {}
        for (var identity :
                List.of(
                        new InvalidIdentity(null, "HOSE"),
                        new InvalidIdentity(WorkType.EXCHANGE, null),
                        new InvalidIdentity(WorkType.EXCHANGE, " "))) {
            context.getBean(JobService.class)
                    .applyStatus(
                            new JobStatusMessage(
                                    target.getId().toString(),
                                    child.toString(),
                                    root.toString(),
                                    identity.type(),
                                    identity.key(),
                                    "SUCCESS",
                                    null,
                                    null,
                                    NOW,
                                    NOW.plusSeconds(1),
                                    1000,
                                    null,
                                    1));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                child))
                .isEqualTo("RUNNING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                root))
                .isEqualTo(parentStatus);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT child_count FROM job_run_step_preparations WHERE parent_execution_id=?",
                                Integer.class,
                                root))
                .isEqualTo(3);
    }

    private static UUID hoseExecution(UUID root) {
        return jdbc.queryForObject(
                "SELECT id FROM job_execution_histories WHERE parent_log_id=? AND meta_json->>'workKey'='HOSE'",
                UUID.class,
                root);
    }

    private static UUID runFor(UUID root) {
        return jdbc.queryForObject(
                "SELECT job_run_id FROM job_execution_histories WHERE id=?", UUID.class, root);
    }

    private static TransactionTemplate transaction() {
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        tx.setTimeout(15);
        return tx;
    }

    private static void setFiniteLockWaits() {
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        jdbc.execute("SET LOCAL statement_timeout='10s'");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(8, TimeUnit.SECONDS)).as("transaction coordination").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Transaction coordination interrupted", interrupted);
        }
    }

    private static void awaitLockWait(int blockedPid, int holderPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT ?=ANY(pg_blocking_pids(?))",
                            Boolean.class,
                            holderPid,
                            blockedPid))) return;
            if (Thread.currentThread().isInterrupted())
                throw new AssertionError("Lock observation interrupted");
            // Back off between actual DB observations; elapsed time alone never opens the gate.
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Transaction did not reach the held row lock");
    }

    private static void withRunLocked(UUID run, Runnable progress) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder =
                    pool.submit(
                            () ->
                                    transaction()
                                            .executeWithoutResult(
                                                    status -> {
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM job_runs WHERE id=? FOR UPDATE",
                                                                UUID.class,
                                                                run);
                                                        locked.countDown();
                                                        awaitLatch(release);
                                                    }));
            try {
                awaitLatch(locked);
                transaction()
                        .executeWithoutResult(
                                status -> {
                                    setFiniteLockWaits();
                                    progress.run();
                                });
            } finally {
                release.countDown();
                holder.get(12, TimeUnit.SECONDS);
            }
        }
    }

    private static void upsertSymbols(UUID job, UUID execution, UUID parent, String code) {
        var json = context.getBean("jsonMapper", tools.jackson.databind.json.JsonMapper.class);
        var symbol =
                new com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage.SymbolRecord(
                        code,
                        "HOSE",
                        null,
                        "Test company",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of());
        var event =
                new com.omni.platform.modules.scheduler.messaging.SymbolUpsertMessage(
                        job, execution, parent, "HOSE", 1, 1, List.of(symbol), NOW);
        context.getBean(com.omni.platform.modules.scheduler.consumers.SymbolUpsertConsumer.class)
                .handleSymbolUpsert(
                        new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                                "test-symbol-upsert",
                                0,
                                0L,
                                "HOSE",
                                json.writeValueAsString(event)));
    }

    private static JobDefinition target(JobType type) {
        return definitions.findAll().stream()
                .filter(d -> d.getJobType() == type)
                .findFirst()
                .orElseThrow();
    }

    private static SchedulerClaim claim(JobDefinition definition) {
        return claims.claimJobDefinition(definition.getId(), NOW, "preparation-test").orElseThrow();
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void assertEmptyIntent() {
        assertThat(count("job_runs")).isZero();
        assertThat(count("job_execution_histories")).isZero();
        assertThat(count("scheduler_outbox_messages")).isZero();
    }
}
