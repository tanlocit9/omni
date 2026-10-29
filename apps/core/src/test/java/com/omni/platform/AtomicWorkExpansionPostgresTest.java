package com.omni.platform;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
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
class AtomicWorkExpansionPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    final JsonMapper json = JsonMapper.builder().build();
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    String schema;

    @BeforeEach
    void baseline() {
        schema = "i8_" + UUID.randomUUID().toString().replace("-", "");
        String url = POSTGRES.getJdbcUrl();
        source =
                new DriverManagerDataSource(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        migration("15").migrate();
    }

    @AfterEach
    void cleanup() {
        assertThat(schema).matches("i8_[a-f0-9]{32}");
        jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    Flyway migration(String target) {
        return Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("filesystem:" + Path.of("../../database/migrations").toAbsolutePath())
                .target(target)
                .load();
    }

    UUID definition() {
        return jdbc.queryForObject(
                "INSERT INTO job_definitions(source,job_type,cron_expr,title)"
                        + " VALUES ('ANALYZER','SYNC_INDICATORS','0 0 19 * * MON-FRI','I8 fixture') RETURNING id",
                UUID.class);
    }

    UUID execution(UUID definition, String meta) {
        return jdbc.queryForObject(
                "INSERT INTO job_execution_histories(job_id,used_source,status,meta_json)"
                        + " VALUES (?,'ANALYZER','RUNNING',?::jsonb) RETURNING id",
                UUID.class,
                definition,
                meta);
    }

    Map<String, Object> atomic(String day, String suffix) {
        String identity = "work:" + suffix.repeat(64);
        return Map.of(
                "workType",
                "SYMBOL",
                "workKey",
                "HOSE-HPG",
                "workBusinessDate",
                day,
                "workIdentity",
                identity,
                "workExpansion",
                Map.of(
                        "jobType",
                        "SYNC_INDICATORS",
                        "workType",
                        "SYMBOL",
                        "workKey",
                        "HOSE-HPG",
                        "businessDate",
                        day,
                        "atomicWorkId",
                        identity,
                        "parameters",
                        Map.of("source", "ANALYZER", "timeframe", "1d")));
    }

    @Test
    void upgradePreservesOldHistoryAndDoesNotFabricateExpansionEvidence() {
        UUID id = execution(definition(), "{\"workType\":\"SYMBOL\",\"workKey\":\"HOSE-HPG\"}");
        String before =
                jdbc.queryForObject(
                        "SELECT to_jsonb(h)::text FROM job_execution_histories h WHERE id=?",
                        String.class,
                        id);
        migration("16").migrate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_jsonb(h)::text FROM job_execution_histories h WHERE id=?",
                                String.class,
                                id))
                .isEqualTo(before);
        migration("16").validate();
        jdbc.update("UPDATE job_execution_histories SET status='SUCCESS' WHERE id=?", id);
    }

    @Test
    void frozenSnapshotRejectsIdentityMutationButAllowsStatusDiagnostics() {
        UUID definition = definition();
        migration("16").migrate();
        UUID id = execution(definition, json.writeValueAsString(atomic("2026-10-29", "a")));
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS',"
                        + "meta_json=meta_json || '{\"recordsProcessed\":1}'::jsonb WHERE id=?",
                id);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE job_execution_histories SET "
                                                + "meta_json=jsonb_set(meta_json,'{workBusinessDate}','\"2026-10-30\"') WHERE id=?",
                                        id))
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE job_execution_histories SET "
                                                + "meta_json=jsonb_set(meta_json,'{workExpansion,parameters,timeframe}','\"1h\"') WHERE id=?",
                                        id))
                .hasMessageContaining("immutable");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("SUCCESS");
    }

    @Test
    void preparedStepRetainsAnchorAndClosesTwoDistinctAtomicDays() {
        UUID definition = definition();
        migration("16").migrate();
        UUID[] ids =
                new TransactionTemplate(new DataSourceTransactionManager(source))
                        .execute(
                                tx -> {
                                    UUID run =
                                            jdbc.queryForObject(
                                                    "INSERT INTO job_runs(mode,actor,idempotency_key,reason,"
                                                            + "request_json,target_definition_id,scope_type,scope_key,business_date,"
                                                            + "path_contract_version,path_fingerprint,step_count)"
                                                            + " VALUES ('MANUAL','i8',?,'fixture','{}',?,'GLOBAL','VN','2026-10-29',1,?,1) RETURNING id",
                                                    UUID.class,
                                                    UUID.randomUUID().toString(),
                                                    definition,
                                                    "a".repeat(64));
                                    jdbc.update(
                                            "INSERT INTO job_run_steps(job_run_id,step_index,job_definition_id,"
                                                    + "definition_source,definition_job_type,definition_cron,scope_type,scope_key,business_date)"
                                                    + " VALUES (?,0,?,'ANALYZER','SYNC_INDICATORS','0 0 19 * * MON-FRI','GLOBAL','VN','2026-10-29')",
                                            run,
                                            definition);
                                    jdbc.update(
                                            "UPDATE job_runs SET state='PREPARED' WHERE id=?", run);
                                    UUID parent =
                                            jdbc.queryForObject(
                                                    "INSERT INTO job_execution_histories(job_id,used_source,status,"
                                                            + "job_run_id,job_run_step_index,meta_json) VALUES (?,'ANALYZER','PENDING',?,0,"
                                                            + "'{\"workType\":\"GLOBAL\",\"workKey\":\"SYNC_INDICATORS\","
                                                            + "\"businessDate\":\"2026-10-29\",\"runPreparationVersion\":\"1\"}') RETURNING id",
                                                    UUID.class,
                                                    definition,
                                                    run);
                                    UUID first = null;
                                    int index = 0;
                                    for (String day : List.of("2026-10-28", "2026-10-29")) {
                                        Map<String, Object> meta =
                                                new LinkedHashMap<>(
                                                        atomic(day, index == 0 ? "b" : "c"));
                                        meta.put("businessDate", "2026-10-29");
                                        meta.put("runPreparationVersion", "1");
                                        UUID child =
                                                jdbc.queryForObject(
                                                        "INSERT INTO job_execution_histories(job_id,parent_log_id,"
                                                                + "used_source,status,job_run_id,job_run_step_index,meta_json)"
                                                                + " VALUES (?,?,'ANALYZER','RUNNING',?,0,?::jsonb) RETURNING id",
                                                        UUID.class,
                                                        definition,
                                                        parent,
                                                        run,
                                                        json.writeValueAsString(meta));
                                        if (first == null) first = child;
                                        jdbc.update(
                                                "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload)"
                                                        + " VALUES (?,?,'fixture',?)",
                                                child,
                                                index++,
                                                json.writeValueAsString(
                                                        Map.of("executionId", child.toString())));
                                    }
                                    jdbc.update(
                                            "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,"
                                                    + "child_count,message_count,closed_at) VALUES (?,0,?,2,2,now())",
                                            run,
                                            parent);
                                    return new UUID[] {run, first};
                                });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories "
                                        + "WHERE job_run_id=? AND parent_log_id IS NOT NULL",
                                Integer.class,
                                ids[0]))
                .isEqualTo(2);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE job_execution_histories SET "
                                                + "meta_json=jsonb_set(meta_json,'{workExpansion,parameters,timeframe}','\"1h\"') WHERE id=?",
                                        ids[1]))
                .hasMessageContaining("immutable");
    }
}
