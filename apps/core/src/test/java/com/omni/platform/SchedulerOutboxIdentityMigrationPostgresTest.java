package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchStore;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.executions.WorkType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@Timeout(120)
class SchedulerOutboxIdentityMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    final JsonMapper json = JsonMapper.builder().build();
    String schema;
    DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;

    @BeforeEach
    void baseline() {
        schema = "i7_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = source(schema);
        jdbc = new JdbcTemplate(dataSource);
        flyway("14").migrate();
    }

    @AfterEach
    void cleanupDisposableSchema() {
        assertThat(schema).matches("i7_[a-f0-9]{32}");
        new JdbcTemplate(source("public")).execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test
    void upgradePreservesEveryIntentHistoryAndDeliveryFieldAcrossStatesAndLeases() {
        for (String status : List.of("PENDING", "PUBLISHED", "BLOCKED")) {
            for (boolean leased : List.of(true, false)) row(status, leased, true);
        }
        row("PENDING", false, false);
        var before = intentsWithoutForeignKey();
        String histories =
                jdbc.queryForObject(
                        "SELECT jsonb_agg(to_jsonb(h) ORDER BY id)::text FROM job_execution_histories h",
                        String.class);
        flyway(null).migrate();
        assertThat(intentsWithoutForeignKey()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT jsonb_agg(to_jsonb(h) ORDER BY id)::text FROM job_execution_histories h",
                                String.class))
                .isEqualTo(histories);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages WHERE execution_id<>(payload::jsonb->>'executionId')::uuid",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages", Integer.class))
                .isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages WHERE claim_token IS NOT NULL",
                                Integer.class))
                .isEqualTo(3);
    }

    @Test
    void restartMigrateAndValidateAreIdempotentAndFreshInstallHasStrictGuard() {
        row("PUBLISHED", false, true);
        flyway(null).migrate();
        var after = intentsWithoutForeignKey();
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        flyway(null).validate();
        assertThat(intentsWithoutForeignKey()).isEqualTo(after);
        assertThat(flyway(null).info().current().getVersion().toString()).isEqualTo("18");
        // A separate disposable schema exercises V1 through V18 from an empty install.
        String original = schema;
        var originalSource = dataSource;
        try {
            schema = "i7_" + UUID.randomUUID().toString().replace("-", "");
            dataSource = source(schema);
            assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(18);
            assertThat(
                            new JdbcTemplate(dataSource)
                                    .queryForObject(
                                            "SELECT count(*) FROM pg_trigger WHERE tgname='guard_scheduler_outbox_work_identity' AND tgrelid='scheduler_outbox_messages'::regclass",
                                            Integer.class))
                    .isEqualTo(1);
        } finally {
            cleanupDisposableSchema();
            schema = original;
            dataSource = originalSource;
        }
    }

    @Test
    void malformedMissingInvalidAndJsonbPoisonIdentityRollBackAllNormalization() {
        for (String payload :
                List.of(
                        "{",
                        "[]",
                        "{}",
                        "{\"executionId\":\"invalid\"}",
                        "{\"x\":1e1000000}",
                        "{\"x\":\"\\u0000\"}")) {
            resetRows();
            row("PENDING", false, true);
            var poison = row("BLOCKED", false, false);
            jdbc.update(
                    "UPDATE scheduler_outbox_messages SET payload=? WHERE id=?",
                    payload,
                    poison.message());
            assertRollback("P14_I7_INVALID");
        }
    }

    @Test
    void absentChildForeignParentDefinitionAndPayloadParentAreRejectedAtomically() {
        for (String conflict :
                List.of("absent", "parent", "definition", "payload-parent", "payload-definition")) {
            resetRows();
            row("PENDING", false, true);
            var invalid = row("PENDING", false, true);
            switch (conflict) {
                case "absent" ->
                        jdbc.update(
                                "UPDATE scheduler_outbox_messages SET payload=replace(payload,?,?) WHERE id=?",
                                invalid.child().toString(),
                                UUID.randomUUID().toString(),
                                invalid.message());
                case "parent" ->
                        jdbc.update(
                                "UPDATE job_execution_histories SET parent_log_id=NULL WHERE id=?",
                                invalid.child());
                case "definition" ->
                        jdbc.update(
                                "UPDATE job_execution_histories SET job_id=? WHERE id=?",
                                definition(),
                                invalid.child());
                case "payload-parent" ->
                        jdbc.update(
                                "UPDATE scheduler_outbox_messages SET payload=replace(payload,?,?) WHERE id=?",
                                invalid.parent().toString(),
                                UUID.randomUUID().toString(),
                                invalid.message());
                case "payload-definition" ->
                        jdbc.update(
                                "UPDATE scheduler_outbox_messages SET payload=(payload::jsonb || jsonb_build_object('jobDefinitionId',?::text))::text WHERE id=?",
                                UUID.randomUUID().toString(),
                                invalid.message());
                default -> throw new IllegalStateException();
            }
            assertRollback("P14_I7_INVALID_IDENTITY");
        }
    }

    @Test
    void projectedUniqueCollisionStopsBeforeChangingAnyReference() {
        var alias = row("PUBLISHED", false, true);
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload) SELECT ?,message_index,topic,payload FROM scheduler_outbox_messages WHERE id=?",
                alias.child(),
                alias.message());
        assertRollback("P14_I7_PROJECTED_UNIQUE_COLLISION");
    }

    @Test
    void postCutoverGuardRejectsOldWriterAliasesAndPoisonButAllowsFencedDeliveryUpdates() {
        var work = row("PENDING", true, true);
        flyway(null).migrate();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE scheduler_outbox_messages SET execution_id=? WHERE id=?",
                                        work.parent(),
                                        work.message()))
                .hasMessageContaining("P14_I7_OUTBOX_WORK_ID_MISMATCH");
        for (String payload :
                List.of("{}", "{", "{\"executionId\":\"invalid\"}", "{\"x\":1e1000000}")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE scheduler_outbox_messages SET payload=? WHERE id=?",
                                            payload,
                                            work.message()))
                    .hasMessageContaining("P14_I7_INVALID");
        }
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='PUBLISHED',published_at=?,claim_token=NULL,claimed_by=NULL,claim_until=NULL WHERE id=?",
                Timestamp.from(NOW),
                work.message());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM scheduler_outbox_messages WHERE id=?",
                                Integer.class,
                                work.message()))
                .isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_id FROM scheduler_outbox_messages WHERE id=?",
                                UUID.class,
                                work.message()))
                .isEqualTo(work.child());
    }

    @Test
    void linkedAliasIsRejectedWithoutChangingCanonicalRunOrExecutionMembership() {
        var alias = row("PENDING", false, true);
        UUID definition =
                jdbc.queryForObject(
                        "SELECT job_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        alias.parent());
        UUID run = createRun(definition);
        jdbc.update(
                "UPDATE job_execution_histories SET job_run_id=?,job_run_step_index=0 WHERE id=?",
                run,
                alias.parent());
        assertRollback("LINKED_RUN_CONFLICT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                                UUID.class,
                                alias.parent()))
                .isEqualTo(run);
    }

    @Test
    void closedJobRunMembershipRemainsByteIdenticalAndItsGuardsStayEnabled() {
        var canonical = row("PENDING", false, false);
        UUID definition =
                jdbc.queryForObject(
                        "SELECT job_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        canonical.parent());
        UUID run = createRun(definition);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(
                        tx -> {
                            jdbc.update(
                                    "UPDATE job_execution_histories SET job_run_id=?,job_run_step_index=0,meta_json=coalesce(meta_json,'{}') || '{\"runPreparationVersion\":\"1\",\"businessDate\":\"2035-01-02\"}'::jsonb WHERE id IN (?,?)",
                                    run,
                                    canonical.parent(),
                                    canonical.child());
                            jdbc.update(
                                    "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) VALUES (?,0,?,1,1,?)",
                                    run,
                                    canonical.parent(),
                                    Timestamp.from(NOW));
                        });
        String snapshot = frozenState();
        var intents = intentsWithoutForeignKey();
        row("BLOCKED", false, true);
        flyway(null).migrate();
        assertThat(frozenState()).isEqualTo(snapshot);
        assertThat(intentsWithoutForeignKey().get(canonical.message()))
                .isEqualTo(intents.get(canonical.message()));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE scheduler_outbox_messages SET message_index=9 WHERE id=?",
                                        canonical.message()))
                .hasMessageContaining("immutable");
    }

    @Test
    void activeWriterLockStopsMigrationAndAQuiescedRetrySucceeds() throws Exception {
        var alias = row("PENDING", false, true);
        var before = intentsWithoutForeignKey();
        try (var connection = dataSource.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "UPDATE job_execution_histories SET status='RUNNING' WHERE id=?")) {
                statement.setObject(1, alias.child());
                statement.executeUpdate();
            }
            var migration = pool.submit(() -> flyway(null).migrate());
            assertThatThrownBy(() -> migration.get(15, TimeUnit.SECONDS))
                    .hasStackTraceContaining("lock timeout");
            connection.rollback();
        }
        assertThat(intentsWithoutForeignKey()).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_id FROM scheduler_outbox_messages WHERE id=?",
                                UUID.class,
                                alias.message()))
                .isEqualTo(alias.parent());
        flyway(null).migrate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_id FROM scheduler_outbox_messages WHERE id=?",
                                UUID.class,
                                alias.message()))
                .isEqualTo(alias.child());
    }

    @Test
    void upgradedAliasUsesActualServicesForApprovalRetryAckAndChildStatusAggregation() {
        var work = row("PENDING", false, true);
        UUID definition =
                jdbc.queryForObject(
                        "SELECT job_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        work.child());
        try (var application =
                PlatformStartupPostgresTest.startApplication(
                        POSTGRES,
                        false,
                        context ->
                                context.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "upgraded-work-schema",
                                                        Map.of(
                                                                "spring.datasource.url",
                                                                dataSource.getUrl()))))) {
            assertThat(application.getBean(Flyway.class).info().current().getVersion().toString())
                    .isEqualTo("18");
            var outbox = application.getBean(SchedulerOutboxService.class);
            var store = application.getBean(JobRunDispatchStore.class);
            var approved = Map.of(DatasetRef.of("eod", Map.of("exchange", "HOSE")), "v1");
            var claim =
                    store.claimLegacy(
                            work.message(), NOW, "upgrade-test", Duration.ofSeconds(30), approved);
            assertThat(claim).isNotNull();
            assertThat(claim.executionId()).isEqualTo(work.child());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT jsonb_exists(meta_json, 'approvedInputs') FROM job_execution_histories WHERE id=?",
                                    Boolean.class,
                                    work.child()))
                    .isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT jsonb_exists(meta_json, 'approvedInputs') FROM job_execution_histories WHERE id=?",
                                    Boolean.class,
                                    work.parent()))
                    .isFalse();
            assertThat(
                            outbox.scheduleRetry(
                                    claim, NOW.plusSeconds(1), "synthetic transport failure"))
                    .isTrue();
            var retried =
                    store.claimLegacy(
                            work.message(),
                            NOW.plusSeconds(2),
                            "upgrade-test",
                            Duration.ofSeconds(30),
                            approved);
            assertThat(retried.executionId()).isEqualTo(work.child());
            assertThat(retried.attempts()).isEqualTo(9);
            assertThat(outbox.renewForPublish(retried, NOW.plusSeconds(2), Duration.ofSeconds(30)))
                    .isTrue();
            assertThat(outbox.markDelivered(retried, NOW.plusSeconds(3))).isTrue();
            application
                    .getBean(JobService.class)
                    .applyStatus(
                            new JobStatusMessage(
                                    definition.toString(),
                                    work.child().toString(),
                                    work.parent().toString(),
                                    WorkType.EXCHANGE,
                                    "HOSE",
                                    "SUCCESS",
                                    Map.of(),
                                    null,
                                    NOW,
                                    NOW.plusSeconds(4),
                                    4000L,
                                    null,
                                    1));
            assertThat(
                            jdbc.queryForList(
                                    "SELECT status FROM job_execution_histories WHERE id IN (?,?)",
                                    String.class,
                                    work.parent(),
                                    work.child()))
                    .containsOnly("SUCCESS");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM scheduler_outbox_messages WHERE id=?",
                                    String.class,
                                    work.message()))
                    .isEqualTo("PUBLISHED");
        }
    }

    private String frozenState() {
        return jdbc.queryForObject(
                "SELECT jsonb_build_object('runs',(SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM job_runs r),'steps',(SELECT jsonb_agg(to_jsonb(s) ORDER BY job_run_id,step_index) FROM job_run_steps s),'closure',(SELECT jsonb_agg(to_jsonb(p) ORDER BY job_run_id,step_index) FROM job_run_step_preparations p))::text",
                String.class);
    }

    private UUID createRun(UUID definition) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(
                        tx -> {
                            UUID run =
                                    jdbc.queryForObject(
                                            "INSERT INTO job_runs(mode,actor,idempotency_key,reason,request_json,target_definition_id,scope_type,scope_key,business_date,path_contract_version,path_fingerprint,step_count) VALUES ('MANUAL','migration-test',?,'fixture','{}',?,'GLOBAL','VN','2035-01-02',1,?,1) RETURNING id",
                                            UUID.class,
                                            UUID.randomUUID().toString(),
                                            definition,
                                            "a".repeat(64));
                            jdbc.update(
                                    "INSERT INTO job_run_steps(job_run_id,step_index,job_definition_id,definition_source,definition_job_type,definition_cron,scope_type,scope_key,business_date) SELECT ?,0,id,source,job_type,cron_expr,'GLOBAL','VN','2035-01-02' FROM job_definitions WHERE id=?",
                                    run,
                                    definition);
                            jdbc.update("UPDATE job_runs SET state='PREPARED' WHERE id=?", run);
                            return run;
                        });
    }

    private void assertRollback(String reason) {
        var before = intentsWithoutForeignKey();
        var foreignKeys =
                jdbc.queryForList(
                        "SELECT execution_id FROM scheduler_outbox_messages ORDER BY id",
                        UUID.class);
        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining(reason);
        assertThat(intentsWithoutForeignKey()).isEqualTo(before);
        assertThat(
                        jdbc.queryForList(
                                "SELECT execution_id FROM scheduler_outbox_messages ORDER BY id",
                                UUID.class))
                .isEqualTo(foreignKeys);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM flyway_schema_history WHERE version='15'",
                                Integer.class))
                .isZero();
    }

    private Map<UUID, String> intentsWithoutForeignKey() {
        return jdbc.query(
                "SELECT id,(to_jsonb(o)-'execution_id')::text AS value FROM scheduler_outbox_messages o",
                rs -> {
                    Map<UUID, String> values = new HashMap<>();
                    while (rs.next())
                        values.put(rs.getObject("id", UUID.class), rs.getString("value"));
                    return values;
                });
    }

    private void resetRows() {
        jdbc.execute("TRUNCATE job_execution_histories CASCADE");
    }

    private UUID definition() {
        return jdbc.queryForObject(
                "INSERT INTO job_definitions(source,job_type,cron_expr,title) VALUES ('VND','SYNC_SYMBOLS',?,'migration fixture') RETURNING id",
                UUID.class,
                UUID.randomUUID().toString());
    }

    private Work row(String status, boolean leased, boolean alias) {
        UUID definition = definition(),
                parent = UUID.randomUUID(),
                child = UUID.randomUUID(),
                message = UUID.randomUUID();
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
                json.writeValueAsString(
                        Map.of(
                                "executionId",
                                child.toString(),
                                "parentExecutionId",
                                parent.toString(),
                                "jobDefinitionId",
                                definition.toString(),
                                "workType",
                                "EXCHANGE",
                                "workKey",
                                "HOSE"));
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(id,execution_id,message_index,topic,message_key,payload,status,attempts,available_at,claim_token,claimed_by,claim_until,last_error) VALUES (?,?,3,'fixture','preserved-key',?,?,7,?,?,?,?, 'preserved-error')",
                message,
                alias ? parent : child,
                payload,
                status,
                Timestamp.from(NOW),
                leased ? UUID.randomUUID() : null,
                leased ? "existing-owner" : null,
                leased ? Timestamp.from(NOW.plusSeconds(60)) : null);
        return new Work(message, parent, child);
    }

    private DriverManagerDataSource source(String schemaName) {
        String url = POSTGRES.getJdbcUrl();
        return new DriverManagerDataSource(
                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schemaName,
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private Flyway flyway(String target) {
        var config =
                Flyway.configure()
                        .dataSource(dataSource)
                        .defaultSchema(schema)
                        .schemas(schema)
                        .locations(
                                "filesystem:"
                                        + Path.of("../../database/migrations")
                                                .toAbsolutePath()
                                                .normalize());
        if (target != null) config.target(target);
        return config.load();
    }

    private record Work(UUID message, UUID parent, UUID child) {}
}
