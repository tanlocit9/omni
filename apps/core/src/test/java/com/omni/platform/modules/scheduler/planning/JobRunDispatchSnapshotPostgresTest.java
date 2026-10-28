package com.omni.platform.modules.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchPlanner.Status;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot.Progress;
import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.RequiredPath;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

import tools.jackson.databind.json.JsonMapper;

/** Disposable PostgreSQL only; no Spring runtime, scheduler, Kafka or provider calls. */
@Testcontainers
@Timeout(60)
class JobRunDispatchSnapshotPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static JobRunRepository repository;
    static JobRunDispatchSnapshotReader reader;
    final JobRunDispatchPlanner planner = new JobRunDispatchPlanner();

    @BeforeAll
    static void migrate() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(source)
                .locations("filesystem:../../database/migrations")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        repository = new JobRunRepository(jdbc, manager, JsonMapper.builder().build());
        reader = new JobRunDispatchSnapshotReader(jdbc, manager);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE job_definitions CASCADE");
        for (String cron : List.of("a", "b", "target")) {
            jdbc.update(
                    "INSERT INTO job_definitions(title,source,job_type,cron_expr) VALUES (?,'VND','SYNC_SYMBOLS',?)",
                    cron,
                    cron);
        }
    }

    @Test
    void actualHundredItemTraceRetainsPublishedRunningAndOpensOnlyAfterSuccess() {
        UUID run = prepare(100, false, "BANKS", ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2));
        assertThat(plan(run, 75).selections())
                .singleElement()
                .satisfies(
                        selection -> {
                            assertThat(selection.stepIndex()).isZero();
                            assertThat(selection.quota()).isEqualTo(75);
                        });
        publish(run, 0, 75);
        var remainder = reader.capture(NOW, List.of(run));
        var upstream = remainder.runs().getFirst().steps().getFirst();
        assertThat(upstream.outbox().eligible()).isEqualTo(25);
        assertThat(upstream.outbox().published()).isEqualTo(75);
        assertThat(upstream.progress()).isEqualTo(Progress.UNFINISHED);
        assertThat(plan(run, 75).selections().getFirst().quota()).isEqualTo(25);
        publish(run, 0, 25);
        assertThat(plan(run, 75).status()).isEqualTo(Status.WAIT);
        // A successful parent alone is insufficient if any persisted child remains running.
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=0 AND parent_log_id IS NULL",
                run);
        assertThat(plan(run, 75).status()).isEqualTo(Status.WAIT);
        succeed(run, 0);
        assertThat(plan(run, 75).selections())
                .singleElement()
                .satisfies(
                        selection -> {
                            assertThat(selection.stepIndex()).isEqualTo(1);
                            assertThat(selection.quota()).isEqualTo(75);
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(attempts) FROM scheduler_outbox_messages", Long.class))
                .isZero();
    }

    @Test
    void joinsFailedBranchesAndOtherRunsRemainIsolatedAcrossDatesScopesAndRestart() {
        UUID join = prepare(2, true, "BANKS", ScopeType.SECTOR, "BANKS", LocalDate.of(2035, 1, 2));
        UUID symbol =
                prepare(1, false, null, ScopeType.SYMBOL, "HOSE:HPG", LocalDate.of(2035, 1, 3));
        succeed(join, 0);
        jdbc.update(
                "UPDATE job_execution_histories SET status='FAILED' WHERE job_run_id=? AND job_run_step_index=1",
                join);
        assertThat(plan(join, 10).status()).isEqualTo(Status.WAIT);
        var restarted = new JobRunDispatchSnapshotReader(jdbc, manager);
        var snapshot = restarted.capture(NOW, List.of(join, symbol));
        assertThat(snapshot.complete()).isTrue();
        var plan = planner.plan(snapshot, 10, null, NOW, Duration.ofSeconds(5));
        assertThat(plan.selections())
                .singleElement()
                .satisfies(
                        selection -> {
                            assertThat(selection.jobRunId()).isEqualTo(symbol);
                            assertThat(selection.scope().type()).isEqualTo(ScopeType.SYMBOL);
                        });
        succeed(join, 1);
        assertThat(plan(join, 10).selections())
                .singleElement()
                .satisfies(selection -> assertThat(selection.stepIndex()).isEqualTo(2));
    }

    @Test
    void leaseDelayPublicationAndBlockedCountsDoNotMultiplyThroughMembershipJoins() {
        UUID run = prepare(4, false, "BANKS", ScopeType.EXCHANGE, "HOSE", LocalDate.of(2035, 1, 2));
        var ids =
                jdbc.queryForList(
                        "SELECT m.id FROM scheduler_outbox_messages m JOIN job_execution_histories h ON h.id=m.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=0 ORDER BY m.id",
                        UUID.class,
                        run);
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET claim_token=?, claimed_by='snapshot-test', claim_until=? WHERE id=?",
                UUID.randomUUID(),
                java.sql.Timestamp.from(NOW.plusSeconds(10)),
                ids.get(0));
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET available_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW.plusSeconds(10)),
                ids.get(1));
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='PUBLISHED' WHERE id=?", ids.get(2));
        var step = reader.capture(NOW, List.of(run)).runs().getFirst().steps().getFirst();
        assertThat(step.outbox()).isEqualTo(new JobRunDispatchSnapshot.Outbox(1, 1, 1, 1, 0, 0));
        assertThat(step.membership().executions()).isEqualTo(5);
        assertThat(step.membership().roots()).isEqualTo(1);
        var expired =
                reader.capture(NOW.plusSeconds(10), List.of(run))
                        .runs()
                        .getFirst()
                        .steps()
                        .getFirst();
        assertThat(expired.outbox().eligible()).isEqualTo(3);
        jdbc.update("UPDATE scheduler_outbox_messages SET status='BLOCKED' WHERE id=?", ids.get(3));
        assertThat(
                        reader.capture(NOW, List.of(run))
                                .runs()
                                .getFirst()
                                .steps()
                                .getFirst()
                                .progress())
                .isEqualTo(Progress.FAILED);
    }

    @Test
    void missingLegacyUnpreparedTerminalAndBoundsFailClosed() {
        UUID missing = UUID.randomUUID();
        assertThat(reader.capture(NOW, List.of(missing)).complete()).isFalse();
        UUID unprepared =
                repository
                        .prepare(
                                request("legacy", "BANKS"),
                                path(false, ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2)))
                        .id();
        var legacy = reader.capture(NOW, List.of(unprepared));
        assertThat(legacy.complete()).isTrue();
        assertThat(legacy.runs().getFirst().complete()).isFalse();
        assertThat(legacy.runs().getFirst().steps())
                .allSatisfy(step -> assertThat(step.progress()).isEqualTo(Progress.UNKNOWN));
        UUID prepared = prepare(0, false, null, ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2));
        assertThat(plan(prepared, 1).status()).isEqualTo(Status.WAIT);
        assertThat(reader.capture(NOW, List.of(prepared)).runs().getFirst().steps())
                .allSatisfy(step -> assertThat(step.progress()).isEqualTo(Progress.SUCCESS));
        jdbc.update("UPDATE job_runs SET state='CANCELLED' WHERE id=?", prepared);
        assertThat(reader.capture(NOW, List.of(prepared)).runs().getFirst().complete()).isFalse();
        assertThat(
                        reader.capture(NOW.minusSeconds(1), List.of(unprepared))
                                .runs()
                                .getFirst()
                                .complete())
                .isFalse();
        assertThatThrownBy(() -> reader.capture(NOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.capture(NOW, List.of(prepared, prepared)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                reader.capture(
                                        NOW,
                                        java.util.stream.IntStream.range(0, 33)
                                                .mapToObj(i -> UUID.randomUUID())
                                                .toList()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncommittedPreparationCannotLeakHalfMembershipAndReaderDoesNotWaitForWriter()
            throws Exception {
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var runId = new java.util.concurrent.atomic.AtomicReference<UUID>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer =
                    executor.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    status -> {
                                                        UUID id =
                                                                prepare(
                                                                        1,
                                                                        false,
                                                                        null,
                                                                        ScopeType.GLOBAL,
                                                                        "VN",
                                                                        LocalDate.of(2035, 1, 2));
                                                        runId.set(id);
                                                        ready.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10, TimeUnit.SECONDS))
                                                                throw new IllegalStateException(
                                                                        "Reader did not finish");
                                                        } catch (InterruptedException error) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(error);
                                                        }
                                                        return id;
                                                    }));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(reader.capture(NOW, List.of(runId.get())).complete()).isFalse();
            } finally {
                release.countDown();
            }
            UUID committed = writer.get(10, TimeUnit.SECONDS);
            assertThat(reader.capture(NOW, List.of(committed)).complete()).isTrue();
        }
    }

    private JobRunDispatchPlanner.Plan plan(UUID run, int budget) {
        return planner.plan(
                reader.capture(NOW, List.of(run)), budget, null, NOW, Duration.ofSeconds(5));
    }

    @Test
    void aggregateStepBoundRejectsWholeWindowWithoutReturningPartialPaths() {
        List<UUID> ids = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            List<RequiredPathResolver.Step> steps = new ArrayList<>();
            for (int index = 0; index < 513; index++) {
                var key =
                        new StepKey(
                                new JobDefinitionIdentity(
                                        DataSource.VND, JobType.SYNC_SYMBOLS, "a"),
                                new Scope(
                                        ScopeType.GLOBAL,
                                        "scope-" + index,
                                        LocalDate.of(2035, 1, 2)));
                steps.add(
                        new RequiredPathResolver.Step(
                                key,
                                TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS),
                                index == 0
                                        ? List.of()
                                        : index == 512
                                                ? steps.stream()
                                                        .map(RequiredPathResolver.Step::key)
                                                        .toList()
                                                : List.of(steps.getFirst().key())));
            }
            ids.add(
                    repository
                            .prepare(
                                    request("large-" + run, null),
                                    new RequiredPath(1, steps.getLast().key(), steps))
                            .id());
        }
        var result = reader.capture(NOW, ids);
        assertThat(result.complete()).isFalse();
        assertThat(result.runs()).isEmpty();
        assertThat(planner.plan(result, 10, null, NOW, Duration.ofSeconds(5)).selections())
                .isEmpty();
    }

    @Test
    void boundedExplainUsesActualSnapshotSqlWithoutMutatingOrClaimingWork() {
        UUID run = prepare(100, true, "BANKS", ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2));
        var before =
                jdbc.queryForList(
                        "SELECT id,status,attempts,claim_token FROM scheduler_outbox_messages ORDER BY id");
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        var explainTransaction = new TransactionTemplate(manager);
        explainTransaction.setReadOnly(true);
        explainTransaction.setTimeout(5);
        String report =
                explainTransaction.execute(
                        status ->
                                named.queryForObject(
                                        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
                                                + JobRunDispatchSnapshotReader.STEPS_SQL,
                                        new org.springframework.jdbc.core.namedparam
                                                        .MapSqlParameterSource("ids", List.of(run))
                                                .addValue("at", java.sql.Timestamp.from(NOW)),
                                        String.class));
        var parsed = JsonMapper.builder().build().readTree(report).get(0);
        assertThat(parsed.get("Plan").get("Actual Rows").asInt()).isEqualTo(3);
        assertThat(parsed.get("Plan").has("Shared Hit Blocks")).isTrue();
        assertThat(parsed.get("Execution Time").asDouble()).isGreaterThanOrEqualTo(0);
        assertThat(
                        jdbc.queryForList(
                                "SELECT id,status,attempts,claim_token FROM scheduler_outbox_messages ORDER BY id"))
                .isEqualTo(before);
        // Preserve actual query evidence only when this approved test is executed. No latency
        // threshold is invented.
        try {
            java.nio.file.Path output =
                    java.nio.file.Path.of("build/reports/job-run-dispatch/snapshot-explain.json");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(output, report);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void aggregateEdgeBoundRejectsDenseCombinedWindowInsteadOfPartialJoinMembership() {
        List<UUID> ids = new ArrayList<>();
        // Each path stays below depth 64, 1024 steps and 8192 edges. Only the
        // combined read window exceeds the edge budget; no database guard is disabled.
        for (int run = 0; run < 5; run++) {
            List<RequiredPathResolver.Step> steps = new ArrayList<>();
            for (int index = 0; index < 63; index++) {
                var key =
                        new StepKey(
                                new JobDefinitionIdentity(
                                        DataSource.VND, JobType.SYNC_SYMBOLS, "a"),
                                new Scope(
                                        ScopeType.GLOBAL,
                                        "scope-" + index,
                                        LocalDate.of(2035, 1, 2)));
                steps.add(
                        new RequiredPathResolver.Step(
                                key,
                                TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS),
                                steps.stream().map(RequiredPathResolver.Step::key).toList()));
            }
            ids.add(
                    repository
                            .prepare(
                                    request("dense-" + run, null),
                                    new RequiredPath(1, steps.getLast().key(), steps))
                            .id());
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_run_step_predecessors", Long.class))
                .isEqualTo(9765L);
        var result = reader.capture(NOW, ids);
        assertThat(result.complete()).isFalse();
        assertThat(result.runs()).isEmpty();
        assertThat(planner.plan(result, 10, null, NOW, Duration.ofSeconds(5)).selections())
                .isEmpty();
    }

    @Test
    void directRootMessagesRequireWorkerSuccessAndFutureClosureCannotOpenBarrier() {
        UUID run = prepare(0, false, null, ScopeType.GLOBAL, "VN", LocalDate.of(2035, 1, 2), true);
        var snapshot = reader.capture(NOW, List.of(run));
        assertThat(snapshot.runs().getFirst().steps().getFirst().membership().expectedChildren())
                .isZero();
        assertThat(snapshot.runs().getFirst().steps().getFirst().outbox().eligible()).isEqualTo(1);
        assertThat(plan(run, 10).selections())
                .singleElement()
                .satisfies(selection -> assertThat(selection.stepIndex()).isZero());
        publish(run, 0, 1);
        assertThat(plan(run, 10).status()).isEqualTo(Status.WAIT);
        succeed(run, 0);
        assertThat(plan(run, 10).selections())
                .singleElement()
                .satisfies(selection -> assertThat(selection.stepIndex()).isEqualTo(1));
        var futureClosure = reader.capture(NOW.minusSeconds(1), List.of(run));
        assertThat(futureClosure.runs().getFirst().complete()).isFalse();
    }

    private static UUID prepare(
            int children, boolean join, String group, ScopeType type, String key, LocalDate date) {
        return prepare(children, join, group, type, key, date, false);
    }

    private static UUID prepare(
            int children,
            boolean join,
            String group,
            ScopeType type,
            String key,
            LocalDate date,
            boolean directRoot) {
        return new TransactionTemplate(manager)
                .execute(
                        status -> {
                            var saved =
                                    repository.prepare(
                                            request(UUID.randomUUID().toString(), group),
                                            path(join, type, key, date));
                            for (int index = 0; index < 3; index++) {
                                UUID parent = UUID.randomUUID();
                                UUID definition = saved.definitionIds().get(index);
                                String meta =
                                        "{\"runPreparationVersion\":\"1\",\"workType\":\"GLOBAL\",\"workKey\":\"VN\",\"businessDate\":\""
                                                + date
                                                + "\"}";
                                insertExecution(
                                        saved.id(),
                                        index,
                                        definition,
                                        parent,
                                        null,
                                        meta,
                                        children == 0 && !directRoot ? "SUCCESS" : "RUNNING");
                                for (int child = 0; child < children; child++) {
                                    UUID execution = UUID.randomUUID();
                                    String childMeta =
                                            meta.replace("\"VN\"", "\"work-" + child + "\"");
                                    insertExecution(
                                            saved.id(),
                                            index,
                                            definition,
                                            execution,
                                            parent,
                                            childMeta,
                                            "RUNNING");
                                    jdbc.update(
                                            "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'test',?,?)",
                                            execution,
                                            "{\"executionId\":\""
                                                    + execution
                                                    + "\",\"parentExecutionId\":\""
                                                    + parent
                                                    + "\"}",
                                            java.sql.Timestamp.from(NOW));
                                }
                                if (directRoot) {
                                    jdbc.update(
                                            "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'test',?,?)",
                                            parent,
                                            "{\"executionId\":\"" + parent + "\"}",
                                            java.sql.Timestamp.from(NOW));
                                }
                                jdbc.update(
                                        "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) VALUES (?,?,?,?,?,?)",
                                        saved.id(),
                                        index,
                                        parent,
                                        children,
                                        directRoot ? 1 : children,
                                        java.sql.Timestamp.from(NOW));
                            }
                            return saved.id();
                        });
    }

    private static void insertExecution(
            UUID run,
            int step,
            UUID definition,
            UUID execution,
            UUID parent,
            String meta,
            String status) {
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,parent_log_id,job_run_id,job_run_step_index,meta_json) VALUES (?,?,'VND',?,?,?,?,CAST(? AS jsonb))",
                execution,
                definition,
                status,
                parent,
                run,
                step,
                meta);
    }

    private static Request request(String key, String group) {
        return new Request(Mode.SCHEDULED, "snapshot-test", key, "snapshot test", group, "{}");
    }

    private static RequiredPath path(boolean join, ScopeType type, String key, LocalDate date) {
        var scope = new Scope(type, key, date);
        List<RequiredPathResolver.Step> steps = new ArrayList<>();
        for (String cron : List.of("a", "b", "target")) {
            var identity =
                    new StepKey(
                            new JobDefinitionIdentity(DataSource.VND, JobType.SYNC_SYMBOLS, cron),
                            scope);
            List<StepKey> predecessors =
                    steps.isEmpty()
                            ? List.of()
                            : join && steps.size() == 1
                                    ? List.of()
                                    : join
                                            ? List.of(steps.get(0).key(), steps.get(1).key())
                                            : List.of(steps.getLast().key());
            steps.add(
                    new RequiredPathResolver.Step(
                            identity,
                            TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS),
                            predecessors));
        }
        return new RequiredPath(1, steps.getLast().key(), steps);
    }

    private static void publish(UUID run, int step, int limit) {
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='PUBLISHED' WHERE id IN (SELECT m.id FROM scheduler_outbox_messages m JOIN job_execution_histories h ON h.id=m.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=? AND m.status='PENDING' ORDER BY m.id LIMIT ?)",
                run,
                step,
                limit);
    }

    private static void succeed(UUID run, int step) {
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=?",
                run,
                step);
    }

    @Test
    void admissionSnapshotCannotAccidentallyOpenANewTransactionOrRunWithoutOne() {
        assertThatThrownBy(() -> reader.captureLocked(NOW, UUID.randomUUID()))
                .hasMessageContaining("requires a transaction");
    }
}
