package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.dependencies.DependencyRegistry;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchCoordinator;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchPlanner;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshotReader;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchStore;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

/** Repeatable synthetic experiment, not an SLA/performance assertion or provider load test. */
class JobRunDispatchBenchmarkPostgresTest {
    static final int WARMUPS = 5, SAMPLES = 30;
    static final Instant NOW = SchedulerDiagnosticsFixture.NOW;
    final JdbcMeasurementProbe probe = new JdbcMeasurementProbe();
    SchedulerDiagnosticsFixture f;
    JobRunDispatchSnapshotReader reader;
    JobRunDispatchStore store;
    SchedulerOutboxService outbox;
    final Path reports = Path.of("build/reports/scheduler-benchmark");

    @Test
    @Timeout(180)
    void recordsSourceBoundDistributionsPlansPoolPressureAndSyntheticStages() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (ConfigurableApplicationContext context =
                    PlatformStartupPostgresTest.startApplication(
                            postgres, false, c -> c.getBeanFactory().addBeanPostProcessor(probe))) {
                f = new SchedulerDiagnosticsFixture(context);
                reader = context.getBean(JobRunDispatchSnapshotReader.class);
                store = context.getBean(JobRunDispatchStore.class);
                outbox = context.getBean(SchedulerOutboxService.class);
                assertThat(probe.pool).isNotNull();
                // Prove the measurement boundary observes a real JDBC execution, not a mock
                // counter.
                probe.reset();
                f.jdbc.queryForObject("SELECT 1", Integer.class);
                assertThat(probe.executions.get()).isEqualTo(1);
                Files.createDirectories(reports);
                var started = Instant.now();
                var scenarios = new ArrayList<Map<String, Object>>();
                scenarios.add(scenario("one-run-100", 1, 100, "ready"));
                scenarios.add(scenario("32-runs-10", 32, 10, "ready"));
                scenarios.add(scenario("join-one-parent-success", 1, 20, "join"));
                scenarios.add(scenario("retry-and-live-lease", 1, 20, "retry"));
                scenarios.add(scenario("unknown-closure", 1, 0, "unknown"));
                scenarios.add(scenario("failed-predecessor", 1, 20, "failed"));
                var stages = syntheticStages();
                var pressure = poolPressure();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("version", 1);
                result.put("startedAt", started.toString());
                result.put("finishedAt", Instant.now().toString());
                result.put(
                        "environment",
                        Map.of(
                                "os",
                                System.getProperty("os.name"),
                                "osVersion",
                                System.getProperty("os.version"),
                                "java",
                                System.getProperty("java.version"),
                                "processors",
                                Runtime.getRuntime().availableProcessors(),
                                "heapMaxBytes",
                                Runtime.getRuntime().maxMemory(),
                                "postgres",
                                f.jdbc.queryForObject("SELECT version()", String.class),
                                "hikariMax",
                                probe.pool.getMaximumPoolSize(),
                                "hikariMinIdle",
                                probe.pool.getMinimumIdle()));
                result.put(
                        "dataset",
                        Map.of(
                                "name",
                                "disposable synthetic scheduler state",
                                "businessDate",
                                "2035-01-02",
                                "provider",
                                "none",
                                "kafka",
                                "mock broker acknowledgement",
                                "worker",
                                "synthetic real applyStatus calls"));
                result.put("warmups", WARMUPS);
                result.put("samples", SAMPLES);
                result.put(
                        "percentiles",
                        "nearest rank; p99 at 30 samples is the observed maximum, not an SLA");
                result.put("scenarios", scenarios);
                result.put("syntheticStages", stages);
                result.put("controlledPoolPressure", pressure);
                result.put("sourceSha256", sourceHashes());
                result.put(
                        "limitations",
                        List.of(
                                "Windows/disposable PostgreSQL measurements are not production baselines",
                                "JDBC execute calls are application execution-call observations, not TCP packet/protocol round trips",
                                "No pg_stat_statements required; its metrics, real provider/network/worker processing times are unavailable",
                                "FIFO comparison measures candidate reading only; FIFO cannot replace the JobRun admission barrier",
                                "Claim timings include rollback; mutating EXPLAIN is restricted to this disposable rollback fixture",
                                "No performance pass/fail threshold, index forcing, new runtime metrics, preparation activation or retained data access"));
                Files.writeString(
                        reports.resolve("benchmark.json"),
                        f.json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            }
        }
    }

    Map<String, Object> scenario(String name, int runs, int children, String state)
            throws Exception {
        f.jdbc.execute("TRUNCATE job_runs,job_execution_histories CASCADE");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < runs; i++)
            ids.add(f.run(children, !state.equals("unknown"), state.equals("join")));
        UUID first = ids.getFirst();
        if (state.equals("join"))
            f.jdbc.update(
                    "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=0",
                    first);
        if (state.equals("failed"))
            f.jdbc.update(
                    "UPDATE job_execution_histories SET status='FAILED' WHERE job_run_id=? AND job_run_step_index=0",
                    first);
        if (state.equals("retry")) {
            f.jdbc.update(
                    "UPDATE scheduler_outbox_messages SET available_at=?",
                    java.sql.Timestamp.from(NOW.plusSeconds(30)));
            f.jdbc.update(
                    "UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='fixture',claim_until=? WHERE id=(SELECT min(id::text)::uuid FROM scheduler_outbox_messages)",
                    UUID.randomUUID(),
                    java.sql.Timestamp.from(NOW.plusSeconds(60)));
        }
        var snapshot = reader.capture(NOW, ids);
        var planner = new JobRunDispatchPlanner();
        var plan = planner.plan(snapshot, 75, null, NOW, Duration.ofSeconds(5));
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("runDiscovery", measure(() -> store.findRunPage(NOW, null, 32)));
        metrics.put("snapshot", measure(() -> reader.capture(NOW, ids)));
        metrics.put(
                "planner",
                measure(() -> planner.plan(snapshot, 75, null, NOW, Duration.ofSeconds(5))));
        if (!plan.selections().isEmpty()) {
            var selected = plan.selections().getFirst();
            metrics.put(
                    "scopedCandidateRead",
                    measure(() -> store.findScopedCandidates(selected, NOW, 75)));
            metrics.put(
                    "fifoCandidateReadReference", measure(() -> outbox.findCandidates(NOW, 75)));
            var candidates = store.findScopedCandidates(selected, NOW, 75);
            UUID message = candidates.getFirst().messageId();
            metrics.put(
                    "claimRollback",
                    measure(
                            () ->
                                    f.tx.execute(
                                            status -> {
                                                var claim =
                                                        store.claimPlanned(
                                                                selected,
                                                                message,
                                                                NOW,
                                                                "benchmark",
                                                                Duration.ofSeconds(30),
                                                                Map.of());
                                                assertThat(claim).isNotNull();
                                                status.setRollbackOnly();
                                                return null;
                                            })));
            Map<String, Object> scope =
                    new LinkedHashMap<>(
                            Map.of(
                                    "run",
                                    selected.jobRunId(),
                                    "step",
                                    selected.stepIndex(),
                                    "definition",
                                    selected.definitionId(),
                                    "type",
                                    selected.scope().type().name(),
                                    "key",
                                    selected.scope().key(),
                                    "date",
                                    selected.scope().businessDate(),
                                    "now",
                                    java.sql.Timestamp.from(NOW),
                                    "limit",
                                    75));
            explain(
                    name + "-snapshot",
                    constant(JobRunDispatchSnapshotReader.class, "STEPS_SQL"),
                    Map.of("ids", ids, "at", java.sql.Timestamp.from(NOW)),
                    false);
            explain(
                    name + "-scoped",
                    constant(JobRunDispatchStore.class, "SCOPED_IDS_SQL"),
                    scope,
                    false);
            explain(
                    name + "-fifo",
                    "SELECT id FROM scheduler_outbox_messages WHERE status='PENDING' AND available_at<=:now AND (claim_until IS NULL OR claim_until<=:now) ORDER BY available_at,created_at,id LIMIT 75",
                    Map.of("now", java.sql.Timestamp.from(NOW)),
                    false);
            scope.put("messageId", message);
            scope.put("claimToken", UUID.randomUUID());
            scope.put("claimedBy", "benchmark");
            scope.put("claimUntil", java.sql.Timestamp.from(NOW.plusSeconds(30)));
            String repo =
                    Files.readString(
                            Path.of(
                                    "src/main/java/com/omni/platform/modules/scheduler/repositories/SchedulerOutboxRepositoryImpl.java"));
            int from = repo.indexOf("public SchedulerOutboxClaim claimEligible");
            int until = repo.indexOf("public List<SchedulerOutboxClaim> claimPending", from);
            var matcher =
                    java.util.regex.Pattern.compile(
                                    "\"\"\"\\s*(UPDATE scheduler_outbox_messages.*?)\"\"\"",
                                    java.util.regex.Pattern.DOTALL)
                            .matcher(repo.substring(from, until));
            assertThat(matcher.find()).isTrue();
            explain(name + "-claim-update", matcher.group(1), scope, true);
            metrics.put("scopedReturnedRows", candidates.size());
            metrics.put("fifoReturnedRows", outbox.findCandidates(NOW, 75).size());
        } else
            metrics.put(
                    "claimRollback",
                    Map.of("samples", 0, "unavailableReason", "NO_ELIGIBLE_SELECTION"));
        explain(
                name + "-diagnostic",
                SchedulerDiagnosticsFixture.sql("run_steps.sql"),
                f.runParameters(ids),
                false);
        explain(
                name + "-inventory",
                SchedulerDiagnosticsFixture.sql("legacy_outbox_inventory.sql"),
                f.legacyParameters("", 100),
                false);
        assertThat(
                        f.jdbc.queryForObject(
                                "SELECT coalesce(sum(attempts),0) FROM scheduler_outbox_messages",
                                Long.class))
                .isZero();
        return Map.of(
                "name",
                name,
                "runs",
                runs,
                "childrenPerStep",
                children,
                "stepsPerRun",
                3,
                "state",
                state,
                "executions",
                f.jdbc.queryForObject("SELECT count(*) FROM job_execution_histories", Long.class),
                "messages",
                f.jdbc.queryForObject("SELECT count(*) FROM scheduler_outbox_messages", Long.class),
                "planStatus",
                plan.status().name(),
                "metrics",
                metrics);
    }

    Map<String, Object> measure(Experiment operation) throws Exception {
        for (int i = 0; i < WARMUPS; i++) operation.run();
        probe.reset();
        List<Long> nanos = new ArrayList<>();
        Instant start = Instant.now();
        for (int i = 0; i < SAMPLES; i++) {
            long at = System.nanoTime();
            operation.run();
            nanos.add(System.nanoTime() - at);
            probe.samplePool();
        }
        return Map.of(
                "elapsedMs",
                JdbcMeasurementProbe.distribution(nanos),
                "windowStart",
                start.toString(),
                "windowEnd",
                Instant.now().toString(),
                "observations",
                probe.snapshot());
    }

    void explain(String name, String sql, Map<String, ?> parameters, boolean writes)
            throws Exception {
        var tx =
                new TransactionTemplate(
                        f.context.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class));
        tx.setReadOnly(!writes);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        String raw =
                tx.execute(
                        status -> {
                            f.jdbc.execute("SET LOCAL statement_timeout='5s'");
                            f.jdbc.execute("SET LOCAL lock_timeout='500ms'");
                            String value =
                                    new NamedParameterJdbcTemplate(f.jdbc)
                                            .queryForObject(
                                                    "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) " + sql,
                                                    parameters,
                                                    String.class);
                            if (writes) status.setRollbackOnly();
                            return value;
                        });
        var node = f.json.readTree(raw).get(0);
        assertThat(node.get("Plan").has("Actual Rows")).isTrue();
        assertThat(node.get("Plan").has("Shared Hit Blocks")).isTrue();
        Files.writeString(reports.resolve(name + "-explain.json"), raw);
    }

    Map<String, Object> syntheticStages() throws Exception {
        f.jdbc.execute("TRUNCATE job_runs,job_execution_histories CASCADE");
        UUID run = f.run(100, true, false);
        var offered = new AtomicInteger();
        var planned = new AtomicInteger();
        var claimed = new AtomicInteger();
        var visited = new AtomicInteger();
        var published = new AtomicInteger();
        var coordinator =
                new JobRunDispatchCoordinator(store, reader, outbox, true, false) {
                    @Override
                    public synchronized Batch load(Instant at, int budget) {
                        var batch = super.load(at, budget);
                        offered.addAndGet(batch.candidates().size());
                        var unique = new java.util.HashMap<String, Integer>();
                        batch.candidates().stream()
                                .filter(c -> c.selection() != null)
                                .forEach(
                                        c ->
                                                unique.put(
                                                        c.selection().jobRunId()
                                                                + ":"
                                                                + c.selection().stepIndex(),
                                                        c.selection().quota()));
                        planned.addAndGet(
                                unique.values().stream().mapToInt(Integer::intValue).sum());
                        return batch;
                    }

                    @Override
                    public SchedulerOutboxClaim claim(
                            JobRunDispatchStore.PlannedCandidate c,
                            Instant at,
                            String owner,
                            Duration lease,
                            Map<com.omni.platform.modules.scheduler.dependencies.DatasetRef, String>
                                    approved) {
                        var value = super.claim(c, at, owner, lease, approved);
                        if (value != null) claimed.incrementAndGet();
                        return value;
                    }
                };
        var real = f.context.getBean(DependencyRegistry.class);
        DependencyRegistry registry =
                request -> {
                    visited.incrementAndGet();
                    return real.evaluate(request);
                };
        KafkaPublisher broker = org.mockito.Mockito.mock(KafkaPublisher.class);
        org.mockito.Mockito.doAnswer(
                        call -> {
                            published.incrementAndGet();
                            return null;
                        })
                .when(broker)
                .publishSerializedAndWait(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        var dispatcher =
                new SchedulerOutboxDispatcher(
                        outbox,
                        registry,
                        broker,
                        new SchedulerProperties(
                                "benchmark",
                                new SchedulerProperties.Claim(Duration.ofMinutes(2), 75)),
                        coordinator);
        probe.reset();
        List<Long> ticks = new ArrayList<>(), statuses = new ArrayList<>();
        int executed = 0;
        Instant started = Instant.now();
        long wall = System.nanoTime();
        for (int step = 0; step < 3; step++) {
            for (int tick = 0; tick < 3; tick++) {
                long at = System.nanoTime();
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        dispatcher, "dispatchBatch", NOW);
                ticks.add(System.nanoTime() - at);
            }
            assertThat(published.get()).isEqualTo((step + 1) * 100);
            var children =
                    f.jdbc.queryForList(
                            "SELECT h.id,h.job_id,h.parent_log_id,h.meta_json->>'workKey' work_key FROM job_execution_histories h WHERE h.job_run_id=? AND h.job_run_step_index=? AND h.parent_log_id IS NOT NULL",
                            run,
                            step);
            for (var child : children) {
                long at = System.nanoTime();
                f.context
                        .getBean(JobService.class)
                        .applyStatus(
                                new JobStatusMessage(
                                        child.get("job_id").toString(),
                                        child.get("id").toString(),
                                        child.get("parent_log_id").toString(),
                                        WorkType.EXCHANGE,
                                        child.get("work_key").toString(),
                                        "SUCCESS",
                                        Map.of(),
                                        null,
                                        NOW,
                                        NOW,
                                        0,
                                        null,
                                        1));
                statuses.add(System.nanoTime() - at);
                executed++;
            }
        }
        double seconds = (System.nanoTime() - wall) / 1e9;
        assertThat(reader.capture(NOW, List.of(run)).runs().getFirst().steps())
                .allMatch(s -> s.progress() == JobRunDispatchSnapshot.Progress.SUCCESS);
        return Map.of(
                "windowStart",
                started.toString(),
                "windowEnd",
                Instant.now().toString(),
                "elapsedSeconds",
                seconds,
                "counts",
                Map.of(
                        "plannedQuota",
                        planned.get(),
                        "offeredCandidates",
                        offered.get(),
                        "registryVisited",
                        visited.get(),
                        "claimed",
                        claimed.get(),
                        "fakeBrokerAcknowledged",
                        published.get(),
                        "syntheticChildStatusSuccess",
                        executed),
                "throughputPerSecond",
                Map.of(
                        "fakePublish",
                        published.get() / seconds,
                        "syntheticStatus",
                        executed / seconds),
                "tickMs",
                JdbcMeasurementProbe.distribution(ticks),
                "statusCallMs",
                JdbcMeasurementProbe.distribution(statuses),
                "observations",
                probe.snapshot());
    }

    Map<String, Object> poolPressure() throws Exception {
        List<Connection> held = new ArrayList<>();
        probe.reset();
        List<Long> durations = java.util.Collections.synchronizedList(new ArrayList<>());
        // Finished synthetic run has no pending rows: use its persisted ID explicitly.
        UUID run = f.jdbc.queryForObject("SELECT id FROM job_runs LIMIT 1", UUID.class);
        try (var threads = Executors.newFixedThreadPool(4)) {
            try {
                for (int i = 0; i < probe.pool.getMaximumPoolSize(); i++)
                    held.add(f.context.getBean(javax.sql.DataSource.class).getConnection());
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++)
                    futures.add(
                            threads.submit(
                                    () -> {
                                        long at = System.nanoTime();
                                        reader.capture(NOW, List.of(run));
                                        durations.add(System.nanoTime() - at);
                                    }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (probe.pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0
                        && System.nanoTime() < deadline) {
                    probe.samplePool();
                    Thread.sleep(1);
                }
                probe.samplePool();
                assertThat(probe.pool.getHikariPoolMXBean().getThreadsAwaitingConnection())
                        .isPositive();
                Thread.sleep(30);
                held.removeLast().close();
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            } finally {
                for (var connection : held) connection.close();
            }
        }
        return Map.of(
                "label",
                "controlled saturation, not normal workload",
                "workers",
                4,
                "heldPoolSlots",
                probe.pool.getMaximumPoolSize(),
                "minimumHoldAfterObservedWaitMs",
                30,
                "snapshotMs",
                JdbcMeasurementProbe.distribution(new ArrayList<>(durations)),
                "observations",
                probe.snapshot());
    }

    Map<String, String> sourceHashes() throws Exception {
        var files =
                List.of(
                        "apps/core/src/test/java/com/omni/platform/JobRunDispatchBenchmarkPostgresTest.java",
                        "apps/core/src/test/java/com/omni/platform/JdbcMeasurementProbe.java",
                        "apps/core/src/test/java/com/omni/platform/SchedulerDiagnosticsFixture.java",
                        "apps/core/src/test/java/com/omni/platform/PlatformStartupPostgresTest.java",
                        "apps/core/src/main/java/com/omni/platform/modules/scheduler/planning/JobRunDispatchSnapshotReader.java",
                        "apps/core/src/main/java/com/omni/platform/modules/scheduler/planning/JobRunDispatchPlanner.java",
                        "apps/core/src/main/java/com/omni/platform/modules/scheduler/planning/JobRunDispatchStore.java",
                        "apps/core/src/main/java/com/omni/platform/modules/scheduler/planning/JobRunDispatchCoordinator.java",
                        "apps/core/src/main/java/com/omni/platform/modules/scheduler/services/SchedulerOutboxService.java",
                        "apps/core/src/main/java/com/omni/platform/modules/scheduler/repositories/SchedulerOutboxRepositoryImpl.java",
                        "database/diagnostics/p14/v1/run_steps.sql",
                        "database/diagnostics/p14/v1/legacy_outbox_inventory.sql");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : files)
            hashes.put(
                    name,
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(Path.of("../..", name)))));
        return hashes;
    }

    static String constant(Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @FunctionalInterface
    interface Experiment {
        void run() throws Exception;
    }
}
