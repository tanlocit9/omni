package com.omni.platform.modules.scheduler.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.omni.platform.modules.scheduler.runs.FrozenJobRunPathTest.key;
import static com.omni.platform.modules.scheduler.runs.FrozenJobRunPathTest.path;
import static com.omni.platform.modules.scheduler.runs.FrozenJobRunPathTest.step;

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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.State;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.RequiredPath;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;

import tools.jackson.databind.json.JsonMapper;

/** No cached Spring context: each transaction/connection closes before the ephemeral database. */
@Testcontainers
@Timeout(60)
class JobRunRepositoryPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String MIGRATIONS = "filesystem:../../database/migrations";
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager manager;
    static JobRunRepository repository;

    @BeforeAll
    static void migrate() {
        var source =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations(MIGRATIONS).load().migrate();
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        repository = new JobRunRepository(jdbc, manager, JsonMapper.builder().build());
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE job_definitions CASCADE");
        for (String cron : List.of("a", "b", "target")) {
            jdbc.update(
                    "INSERT INTO job_definitions (title, source, job_type, cron_expr) VALUES (?, 'VND', 'SYNC_SYMBOLS', ?)",
                    cron,
                    cron);
        }
    }

    @Test
    void persistsCompleteJoinAndReloadsAfterDeclarationsAndApplicationInstancesChange() {
        var resolved = join();
        var saved = repository.prepare(request("request-1"), resolved);
        assertThat(saved.state()).isEqualTo(State.PREPARED);
        assertThat(saved.path().predecessors())
                .containsExactly(List.of(), List.of(), List.of(0, 1));
        assertThat(saved.definitionIds()).hasSize(3);
        jdbc.update(
                "UPDATE job_definitions SET config_json = '{}'::jsonb, cron_expr = 'new-cron' WHERE id = ?",
                saved.definitionIds().getLast());
        var restarted = new JobRunRepository(jdbc, manager, JsonMapper.builder().build());
        assertThat(restarted.findById(saved.id())).contains(saved);
        assertThat(restarted.prepare(request("request-1"), resolved)).isEqualTo(saved);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages", Integer.class))
                .isZero();
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void replaysJsonObjectsSemanticallyAndRejectsReuseWithDifferentRequestOrPath() {
        var original =
                new Request(
                        Mode.MANUAL, "operator", "key", "research", "BANKS", "{\"a\":1,\"b\":2}");
        var saved = repository.prepare(original, join());
        assertThat(
                        repository
                                .prepare(
                                        new Request(
                                                Mode.MANUAL,
                                                "operator",
                                                "key",
                                                "research",
                                                "BANKS",
                                                "{\"b\":2,\"a\":1}"),
                                        join())
                                .id())
                .isEqualTo(saved.id());
        for (Request changed :
                List.of(
                        new Request(
                                Mode.MANUAL,
                                "operator",
                                "key",
                                "changed",
                                "BANKS",
                                "{\"a\":1,\"b\":2}"),
                        new Request(
                                Mode.MANUAL,
                                "operator",
                                "key",
                                "research",
                                "OTHER",
                                "{\"a\":1,\"b\":2}"),
                        new Request(Mode.MANUAL, "operator", "key", "research", "BANKS", "{}"))) {
            assertThatThrownBy(() -> repository.prepare(changed, join()))
                    .hasMessageContaining("IDEMPOTENCY_CONFLICT");
        }
        assertThatThrownBy(() -> repository.prepare(original, path(step(key("a")))))
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThat(
                        repository
                                .prepare(
                                        new Request(
                                                Mode.SCHEDULED,
                                                "operator",
                                                "key",
                                                "research",
                                                null,
                                                "{}"),
                                        join())
                                .id())
                .isNotEqualTo(saved.id());
    }

    @Test
    void concurrentRetriesCreateExactlyOneSealedSnapshot() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks =
                    java.util.stream.IntStream.range(0, 4)
                            .mapToObj(
                                    index ->
                                            executor.submit(
                                                    () -> {
                                                        assertThat(
                                                                        start.await(
                                                                                10,
                                                                                TimeUnit.SECONDS))
                                                                .isTrue();
                                                        return repository
                                                                .prepare(request("race"), join())
                                                                .id();
                                                    }))
                            .toList();
            start.countDown();
            var ids = new java.util.HashSet<UUID>();
            for (var task : tasks) ids.add(task.get(20, TimeUnit.SECONDS));
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_run_steps", Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_run_step_predecessors", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void rollsBackTheWholeSnapshotOnOuterFailureAndCanRetry() {
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                transaction -> {
                                                    repository.prepare(request("rollback"), join());
                                                    throw new IllegalStateException(
                                                            "crash before commit");
                                                }))
                .hasMessageContaining("crash before commit");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_run_steps", Integer.class))
                .isZero();
        assertThat(repository.prepare(request("rollback"), join()).state())
                .isEqualTo(State.PREPARED);
    }

    @Test
    void rejectsMissingDefinitionsAndDisabledBackfillAndInvalidJsonWithoutRows() {
        assertThatThrownBy(
                        () ->
                                repository.prepare(
                                        request("missing"), path(step(key("not-persisted")))))
                .hasMessageContaining("Unpersisted");
        assertThatThrownBy(
                        () ->
                                repository.prepare(
                                        new Request(
                                                Mode.BACKFILL,
                                                "operator",
                                                "key",
                                                "reason",
                                                null,
                                                "{}"),
                                        join()))
                .hasMessageContaining("reserved");
        assertThatThrownBy(
                        () ->
                                repository.prepare(
                                        new Request(
                                                Mode.MANUAL,
                                                "operator",
                                                "key",
                                                "reason",
                                                null,
                                                "[]"),
                                        join()))
                .hasMessageContaining("JSON object");
        assertThatThrownBy(
                        () ->
                                repository.prepare(
                                        new Request(
                                                Mode.MANUAL,
                                                "operator",
                                                "key",
                                                "reason",
                                                null,
                                                "invalid"),
                                        join()))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class)).isZero();
    }

    @Test
    void storesEffectiveUpstreamScopesAndKeepsDatesAndSymbolRequestsIndependent() {
        var root =
                new StepKey(
                        key("a").definition(),
                        new Scope(ScopeType.EXCHANGE, "HOSE", FrozenJobRunPathTest.DATE));
        var target =
                new StepKey(
                        key("target").definition(),
                        new Scope(ScopeType.SYMBOL, "HOSE:HPG", FrozenJobRunPathTest.DATE));
        var saved = repository.prepare(request("hpg"), path(step(root), step(target, root)));
        assertThat(saved.path().requiredPath().steps())
                .extracting(s -> s.key().scope().type())
                .containsExactly(ScopeType.EXCHANGE, ScopeType.SYMBOL);
        for (Scope scope :
                List.of(
                        new Scope(ScopeType.SYMBOL, "HOSE:FPT", FrozenJobRunPathTest.DATE),
                        new Scope(
                                ScopeType.SYMBOL,
                                "HOSE:HPG",
                                FrozenJobRunPathTest.DATE.plusDays(1)),
                        new Scope(ScopeType.SECTOR, "BANKS", FrozenJobRunPathTest.DATE))) {
            var key = new StepKey(target.definition(), scope);
            assertThat(
                            repository
                                    .prepare(
                                            request(scope.key() + ":" + scope.businessDate()),
                                            path(step(key)))
                                    .id())
                    .isNotEqualTo(saved.id());
        }
        // These are persisted scope declarations only; no producer capability or shared work is
        // activated.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages", Integer.class))
                .isZero();
    }

    @Test
    void databasePreventsRewritingOrAppendingSealedMembershipAndDeletingHistory() {
        var saved = repository.prepare(request("immutable"), join());
        for (String sql :
                List.of(
                        "UPDATE job_run_steps SET scope_key='changed' WHERE job_run_id=?",
                        "DELETE FROM job_run_steps WHERE job_run_id=?",
                        "UPDATE job_run_step_predecessors SET predecessor_index=0 WHERE job_run_id=?",
                        "DELETE FROM job_run_step_predecessors WHERE job_run_id=?",
                        "UPDATE job_runs SET reason='changed' WHERE id=?",
                        "UPDATE job_runs SET state='PREPARING' WHERE id=?",
                        "DELETE FROM job_runs WHERE id=?",
                        "INSERT INTO job_run_step_predecessors VALUES (?, 2, 0)")) {
            assertThatThrownBy(() -> jdbc.update(sql, saved.id()))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO job_run_steps SELECT job_run_id, 3, job_definition_id, definition_source,
                    definition_job_type, definition_cron, 'SYMBOL', 'OTHER', business_date
                FROM job_run_steps WHERE job_run_id=? AND step_index=0
                """,
                                        saved.id()))
                .isInstanceOf(DataAccessException.class);
        jdbc.update(
                "UPDATE job_runs SET state='RUNNING', failure_reason='diagnostic' WHERE id=?",
                saved.id());
        assertThat(repository.findById(saved.id()).orElseThrow().state()).isEqualTo(State.RUNNING);
        assertThat(repository.findById(saved.id()).orElseThrow().failureReason())
                .isEqualTo("diagnostic");
    }

    @Test
    void databaseRejectsIncompleteCommitsAndMalformedSealTransitions() {
        var template = repository.prepare(request("template"), join());
        for (String requestedState : List.of("PREPARING", "PREPARED")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            """
                    INSERT INTO job_runs SELECT ?, requested_at, mode, actor, ?, reason, business_group,
                        request_json, target_definition_id, scope_type, scope_key, business_date,
                        path_contract_version, path_fingerprint, step_count, ?, failure_reason
                    FROM job_runs WHERE id=?
                    """,
                                            UUID.randomUUID(),
                                            requestedState,
                                            requestedState,
                                            template.id()))
                    .isInstanceOf(DataAccessException.class);
        }
        for (String nextState : List.of("RUNNING", "PREPARED")) {
            assertThatThrownBy(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    transaction -> {
                                                        UUID id = UUID.randomUUID();
                                                        jdbc.update(
                                                                """
                        INSERT INTO job_runs SELECT ?, requested_at, mode, actor, ?, reason, business_group,
                            request_json, target_definition_id, scope_type, scope_key, business_date,
                            path_contract_version, path_fingerprint, step_count, 'PREPARING', failure_reason
                        FROM job_runs WHERE id=?
                        """,
                                                                id,
                                                                id.toString(),
                                                                template.id());
                                                        jdbc.update(
                                                                "UPDATE job_runs SET state=? WHERE id=?",
                                                                nextState,
                                                                id);
                                                    }))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void executionLinksEnforceRunStepDefinitionAndParentOwnershipWhileLegacyRowsStillWork() {
        var one = repository.prepare(request("one"), join());
        var two = repository.prepare(request("two"), join());
        UUID parent = execution(one.definitionIds().getFirst(), null, one.id(), 0);
        execution(one.definitionIds().getLast(), parent, one.id(), 2);
        UUID legacy = execution(one.definitionIds().getFirst(), null, null, null);
        execution(one.definitionIds().getFirst(), legacy, null, null);
        assertThatThrownBy(() -> execution(one.definitionIds().getLast(), null, one.id(), 0))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> execution(two.definitionIds().getFirst(), parent, two.id(), 0))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> execution(one.definitionIds().getFirst(), null, one.id(), null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM job_definitions WHERE id=?",
                                        one.definitionIds().getFirst()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void forwardMigrationPreservesExistingManualExecutionAndPendingOutboxRows() {
        jdbc.execute("CREATE SCHEMA legacy_upgrade");
        String url =
                POSTGRES.getJdbcUrl()
                        + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                        + "currentSchema=legacy_upgrade";
        var source =
                new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(source)
                .defaultSchema("legacy_upgrade")
                .locations(MIGRATIONS)
                .target("11")
                .load()
                .migrate();
        var legacy = new JdbcTemplate(source);
        UUID definition = UUID.randomUUID(), execution = UUID.randomUUID();
        legacy.update(
                "INSERT INTO job_definitions (id,title,source,job_type,cron_expr) VALUES (?,'legacy','VND','SYNC_SYMBOLS','legacy')",
                definition);
        legacy.update(
                "INSERT INTO job_execution_histories (id,job_id,used_source,status) VALUES (?,?,'VND','RUNNING')",
                execution,
                definition);
        legacy.update(
                """
                INSERT INTO manual_job_triggers (job_definition_id,execution_id,actor,idempotency_key,reason,state,requested_at)
                VALUES (?,?,'operator','legacy','research','ACCEPTED',NOW())
                """,
                definition,
                execution);
        legacy.update(
                "INSERT INTO scheduler_outbox_messages (execution_id,message_index,topic,payload) VALUES (?,0,'legacy','{}')",
                execution);
        assertThat(legacy.queryForObject("SELECT count(*) FROM manual_job_triggers", Integer.class))
                .isEqualTo(1);
        Flyway.configure()
                .dataSource(source)
                .defaultSchema("legacy_upgrade")
                .locations(MIGRATIONS)
                .target("13")
                .load()
                .migrate();
        var retainedRuns =
                new JobRunRepository(
                        legacy,
                        new DataSourceTransactionManager(source),
                        JsonMapper.builder().build());
        var retained =
                retainedRuns.prepare(request("retain-through-v14"), path(step(key("legacy"))));
        Flyway.configure()
                .dataSource(source)
                .defaultSchema("legacy_upgrade")
                .locations(MIGRATIONS)
                .load()
                .migrate();
        assertThat(
                        legacy.queryForMap(
                                "SELECT job_run_id,job_run_step_index,status FROM job_execution_histories WHERE id=?",
                                execution))
                .containsEntry("job_run_id", null)
                .containsEntry("job_run_step_index", null)
                .containsEntry("status", "RUNNING");
        assertThat(
                        legacy.queryForObject(
                                "SELECT to_regclass('legacy_upgrade.manual_job_triggers') IS NULL",
                                Boolean.class))
                .isTrue(); // V14 intentionally drops only deferred manual request audit.
        assertThat(retainedRuns.findById(retained.id())).contains(retained);
        assertThat(
                        legacy.queryForObject(
                                "SELECT status FROM scheduler_outbox_messages", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void sealingRejectsDisconnectedMembershipWrongTargetScopeAndCrossDateSteps() {
        var template = repository.prepare(request("seal-template"), join());
        for (String fault : List.of("disconnected", "wrong-scope", "cross-date", "backward-edge")) {
            assertThatThrownBy(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    transaction -> {
                                                        UUID id = UUID.randomUUID();
                                                        cloneUnsealed(template.id(), id, 3);
                                                        jdbc.update(
                                                                """
                        INSERT INTO job_run_steps
                        SELECT ?, step_index, job_definition_id, definition_source, definition_job_type,
                            definition_cron, scope_type,
                            CASE WHEN ? AND step_index=2 THEN 'OTHER' ELSE scope_key END,
                            business_date + CASE WHEN ? AND step_index=0 THEN 1 ELSE 0 END
                        FROM job_run_steps WHERE job_run_id=?
                        """,
                                                                id,
                                                                fault.equals("wrong-scope"),
                                                                fault.equals("cross-date"),
                                                                template.id());
                                                        if (!fault.equals("disconnected")) {
                                                            jdbc.update(
                                                                    "INSERT INTO job_run_step_predecessors SELECT ?,step_index,predecessor_index "
                                                                            + "FROM job_run_step_predecessors WHERE job_run_id=?",
                                                                    id,
                                                                    template.id());
                                                        }
                                                        if (fault.equals("backward-edge")) {
                                                            jdbc.update(
                                                                    "INSERT INTO job_run_step_predecessors VALUES (?,0,1)",
                                                                    id);
                                                        }
                                                        jdbc.update(
                                                                "UPDATE job_runs SET state='PREPARED' WHERE id=?",
                                                                id);
                                                    }))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining(
                            switch (fault) {
                                case "disconnected" -> "disconnected membership";
                                case "backward-edge" -> "check constraint";
                                default -> "membership is incomplete";
                            });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void databaseEnforcesTheEdgeBoundBeforeSealing() {
        var template = repository.prepare(request("edge-template"), join());
        assertThatThrownBy(
                        () ->
                                new TransactionTemplate(manager)
                                        .executeWithoutResult(
                                                transaction -> {
                                                    UUID id = UUID.randomUUID();
                                                    cloneUnsealed(template.id(), id, 130);
                                                    jdbc.update(
                                                            """
                    INSERT INTO job_run_steps
                    SELECT ?, n, s.job_definition_id, s.definition_source, s.definition_job_type,
                        s.definition_cron, s.scope_type,
                        CASE WHEN n=129 THEN s.scope_key ELSE 'scope-' || n END, s.business_date
                    FROM job_run_steps s CROSS JOIN generate_series(0,129) n
                    WHERE s.job_run_id=? AND s.step_index=2
                    """,
                                                            id,
                                                            template.id());
                                                    jdbc.update(
                                                            """
                    INSERT INTO job_run_step_predecessors
                    SELECT ?, dependent, upstream FROM generate_series(0,129) dependent
                        CROSS JOIN generate_series(0,129) upstream WHERE upstream < dependent
                    """,
                                                            id);
                                                    jdbc.update(
                                                            "UPDATE job_runs SET state='PREPARED' WHERE id=?",
                                                            id);
                                                }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("edge bound");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class))
                .isEqualTo(1);
    }

    private void cloneUnsealed(UUID template, UUID id, int count) {
        jdbc.update(
                """
                INSERT INTO job_runs SELECT ?,requested_at,mode,actor,?,reason,business_group,
                    request_json,target_definition_id,scope_type,scope_key,business_date,
                    path_contract_version,path_fingerprint,?,'PREPARING',failure_reason
                FROM job_runs WHERE id=?
                """,
                id,
                id.toString(),
                count,
                template);
    }

    private UUID execution(UUID definition, UUID parent, UUID run, Integer index) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO job_execution_histories (id,job_id,parent_log_id,used_source,status,job_run_id,job_run_step_index)
                VALUES (?,?,?,'VND','PENDING',?,?)
                """,
                id,
                definition,
                parent,
                run,
                index);
        return id;
    }

    private static Request request(String key) {
        return new Request(Mode.MANUAL, "operator", key, "research", null, "{}");
    }

    private static RequiredPath join() {
        return path(step(key("a")), step(key("b")), step(key("target"), key("a"), key("b")));
    }
}
