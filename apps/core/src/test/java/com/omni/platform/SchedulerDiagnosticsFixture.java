package com.omni.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Disposable-database fixture only; never used by application/runtime diagnostics. */
final class SchedulerDiagnosticsFixture {
    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    final ConfigurableApplicationContext context;
    final JdbcTemplate jdbc;
    final JsonMapper json;
    final TransactionTemplate tx;

    SchedulerDiagnosticsFixture(ConfigurableApplicationContext context) {
        this.context = context;
        jdbc = context.getBean(JdbcTemplate.class);
        json = context.getBean("jsonMapper", JsonMapper.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        for (String cron : List.of("diag-a", "diag-b", "diag-target"))
            jdbc.update(
                    "INSERT INTO job_definitions(title,source,job_type,cron_expr) VALUES (?,'VND','SYNC_SYMBOLS',?) ON CONFLICT (source,job_type,cron_expr) DO NOTHING",
                    cron,
                    cron);
    }

    UUID run(int children, boolean closed, boolean join) {
        return tx.execute(
                status -> {
                    List<RequiredPathResolver.Step> steps = new ArrayList<>();
                    for (String cron : List.of("diag-a", "diag-b", "diag-target")) {
                        var key =
                                new RequiredPathResolver.StepKey(
                                        new JobDefinitionIdentity(
                                                DataSource.VND, JobType.SYNC_SYMBOLS, cron),
                                        new RequiredPathResolver.Scope(
                                                RequiredPathResolver.ScopeType.GLOBAL,
                                                "VN",
                                                LocalDate.of(2035, 1, 2)));
                        var predecessors =
                                steps.isEmpty() || join && steps.size() == 1
                                        ? List.<RequiredPathResolver.StepKey>of()
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
                            context.getBean(JobRunRepository.class)
                                    .prepare(
                                            new JobRunSnapshot.Request(
                                                    JobRunSnapshot.Mode.MANUAL,
                                                    "diagnostic-fixture",
                                                    UUID.randomUUID().toString(),
                                                    "disposable diagnostic fixture",
                                                    null,
                                                    "{}"),
                                            new RequiredPathResolver.RequiredPath(
                                                    1, steps.getLast().key(), steps));
                    if (!closed) return saved.id();
                    for (int step = 0; step < 3; step++) {
                        UUID parent = UUID.randomUUID();
                        jdbc.update(
                                "INSERT INTO job_execution_histories(id,job_id,used_source,status,job_run_id,job_run_step_index,meta_json) VALUES (?,?,'VND','RUNNING',?,?,CAST(? AS jsonb))",
                                parent,
                                saved.definitionIds().get(step),
                                saved.id(),
                                step,
                                metadata("GLOBAL", "VN"));
                        for (int index = 0; index < children; index++) {
                            UUID child = UUID.randomUUID();
                            String key = "work-" + index;
                            jdbc.update(
                                    "INSERT INTO job_execution_histories(id,job_id,used_source,status,parent_log_id,job_run_id,job_run_step_index,meta_json) VALUES (?,?,'VND','RUNNING',?,?,?,CAST(? AS jsonb))",
                                    child,
                                    saved.definitionIds().get(step),
                                    parent,
                                    saved.id(),
                                    step,
                                    metadata("EXCHANGE", key));
                            jdbc.update(
                                    "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload,available_at) VALUES (?,0,'diagnostic',?,?)",
                                    child,
                                    json.writeValueAsString(
                                            Map.of(
                                                    "executionId",
                                                    child.toString(),
                                                    "parentExecutionId",
                                                    parent.toString(),
                                                    "workType",
                                                    "EXCHANGE",
                                                    "workKey",
                                                    key)),
                                    java.sql.Timestamp.from(NOW));
                        }
                        jdbc.update(
                                "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) VALUES (?,?,?,?,?,?)",
                                saved.id(),
                                step,
                                parent,
                                children,
                                children,
                                java.sql.Timestamp.from(NOW));
                    }
                    return saved.id();
                });
    }

    private String metadata(String type, String key) {
        return json.writeValueAsString(
                Map.of(
                        "runPreparationVersion",
                        "1",
                        "businessDate",
                        "2035-01-02",
                        "workType",
                        type,
                        "workKey",
                        key));
    }

    UUID legacy(String shape, String state) {
        UUID definition =
                jdbc.queryForObject(
                        "SELECT id FROM job_definitions WHERE source='VND' AND cron_expr='diag-a'",
                        UUID.class);
        UUID parent = UUID.randomUUID(), child = UUID.randomUUID(), message = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,meta_json) VALUES (?,?,'VND','RUNNING','{}')",
                parent,
                definition);
        jdbc.update(
                "INSERT INTO job_execution_histories(id,job_id,used_source,status,parent_log_id,meta_json) VALUES (?,?,'VND','RUNNING',?,'{\"workType\":\"EXCHANGE\",\"workKey\":\"HOSE\"}')",
                child,
                definition,
                parent);
        String payload =
                switch (shape) {
                    case "malformed" -> "{";
                    case "array" -> "[]";
                    case "missing" -> "{}";
                    case "invalid" -> "{\"executionId\":\"invalid\"}";
                    case "absent" ->
                            json.writeValueAsString(
                                    Map.of("executionId", UUID.randomUUID().toString()));
                    default ->
                            json.writeValueAsString(
                                    Map.of(
                                            "executionId",
                                            child.toString(),
                                            "parentExecutionId",
                                            parent.toString()));
                };
        jdbc.update(
                "INSERT INTO scheduler_outbox_messages(id,execution_id,message_index,topic,payload,status,available_at) VALUES (?,?,0,'diagnostic',?,?,?)",
                message,
                shape.equals("current") ? child : parent,
                payload,
                state,
                java.sql.Timestamp.from(NOW));
        return message;
    }

    JsonNode query(String name, Map<String, ?> parameters) throws Exception {
        String sql = sql(name);
        var read = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return read.execute(
                status -> {
                    jdbc.execute("SET LOCAL statement_timeout='5s'");
                    jdbc.execute("SET LOCAL lock_timeout='500ms'");
                    return json.readTree(
                            new NamedParameterJdbcTemplate(jdbc)
                                    .queryForObject(sql, parameters, String.class));
                });
    }

    static String sql(String name) throws Exception {
        return Files.readString(Path.of("../../database/diagnostics/p14/v1", name))
                .replaceAll(":'([a-z_]+)'", ":$1");
    }

    Map<String, Object> runParameters(List<UUID> ids) {
        return Map.of(
                "run_ids",
                json.writeValueAsString(ids.stream().map(UUID::toString).toList()),
                "observed_at",
                NOW.toString());
    }

    Map<String, Object> legacyParameters(String after, int limit) {
        return Map.of("after_id", after, "row_limit", limit, "observed_at", NOW.toString());
    }
}
