package com.omni.platform.modules.scheduler.runs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.projections.SymbolKeyProjection;
import com.omni.platform.shared.executions.WorkIdentity;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Transaction-local, bounded fan-out builder. No thread-local or authoritative process cache. */
public final class JobRunWorkContext {
    private final JobExecutionHistory parent;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final int limit;
    private boolean preview;
    private final List<JobExecutionHistory> children = new ArrayList<>();
    private final Map<UUID, JobExecutionHistory> childrenById = new HashMap<>();
    private final Set<String> identities = new HashSet<>();

    public JobRunWorkContext(
            JobExecutionHistory parent,
            EntityManager entities,
            JdbcTemplate jdbc,
            JsonMapper json,
            int limit) {
        if (limit < 0 || limit > 5000) throw new IllegalArgumentException("Invalid work bound");
        this.parent = parent;
        this.entities = entities;
        this.jdbc = jdbc;
        this.json = json;
        this.limit = limit;
    }

    /** Detached, read-only expansion context. It cannot persist executions or enqueue intent. */
    public static JobRunWorkContext preview(
            JobExecutionHistory parent, JdbcTemplate jdbc, JsonMapper json) {
        return preview(parent, jdbc, json, 5000);
    }

    public static JobRunWorkContext preview(
            JobExecutionHistory parent, JdbcTemplate jdbc, JsonMapper json, int limit) {
        var context = new JobRunWorkContext(parent, null, jdbc, json, limit);
        context.preview = true;
        return context;
    }

    public JobExecutionHistory child(
            WorkIdentity identity, Map<String, Object> metadata, Instant now) {
        if (children.size() >= limit)
            throw new IllegalArgumentException("JOB_RUN_WORK_BOUND_EXCEEDED");
        if (!identities.add(identity + ":" + metadata.get("workBusinessDate")))
            throw new IllegalArgumentException("JOB_RUN_DUPLICATE_WORK");
        JobExecutionHistory child = new JobExecutionHistory();
        child.setJob(parent.getJob());
        child.setUsedSource(parent.getUsedSource());
        child.setParentLogId(parent.getId());
        child.setJobRunId(parent.getJobRunId());
        child.setJobRunStepIndex(parent.getJobRunStepIndex());
        child.setTriggeredAt(now);
        child.setStartedAt(now);
        child.setStatus(JobStatus.RUNNING);
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        values.remove("symbolKey");
        values.put("workType", identity.type().name());
        values.put("workKey", identity.key());
        values.put("businessDate", parent.getMetaJson().get("businessDate"));
        values.put("runPreparationVersion", "1");
        child.setMetaJson(values);
        if (preview) child.setId(UUID.randomUUID());
        else entities.persist(child);
        children.add(child);
        childrenById.put(child.getId(), child);
        return child;
    }

    public void snapshot(UUID executionId, Map<String, Object> snapshot, String source) {
        var child = Objects.requireNonNull(childrenById.get(executionId), "Unknown child");
        Map<String, Object> meta = new LinkedHashMap<>(child.getMetaJson());
        meta.put("workExpansion", snapshot);
        meta.put("workBusinessDate", snapshot.get("businessDate"));
        meta.put("workIdentity", snapshot.get("atomicWorkId"));
        child.setUsedSource(JobDefinition.DataSource.valueOf(source));
        child.setMetaJson(meta);
    }

    public List<JobExecutionHistory> children() {
        return List.copyOf(children);
    }

    public List<SymbolKeyProjection> symbols(String[] sectors, int level) {
        String filter = json.writeValueAsString(sectors == null ? List.of() : List.of(sectors));
        return bounded(
                jdbc.query(
                        """
                SELECT code, exchange FROM symbols WHERE is_active = true
                  AND (CAST(? AS jsonb) = '[]'::jsonb
                    OR jsonb_exists(CAST(? AS jsonb), meta_json ->> CAST(? AS text)))
                ORDER BY exchange, code LIMIT ?
                """,
                        (rs, row) ->
                                (SymbolKeyProjection)
                                        new Symbol(rs.getString("code"), rs.getString("exchange")),
                        filter,
                        filter,
                        "sectorLv" + level + "Code",
                        limit + 1));
    }

    public List<SymbolKeyProjection> symbolsByExchange(String exchange) {
        return bounded(
                jdbc.query(
                        """
                SELECT code, exchange FROM symbols WHERE is_active = true AND upper(exchange) = upper(?)
                ORDER BY exchange, code LIMIT ?
                """,
                        (rs, row) ->
                                (SymbolKeyProjection)
                                        new Symbol(rs.getString("code"), rs.getString("exchange")),
                        exchange,
                        limit + 1));
    }

    public List<String> sectors(String[] sectors, int level) {
        String filter = json.writeValueAsString(sectors == null ? List.of() : List.of(sectors));
        return bounded(
                jdbc.query(
                        """
                SELECT DISTINCT meta_json ->> CAST(? AS text) AS sector_code FROM symbols
                WHERE is_active = true AND meta_json ->> CAST(? AS text) IS NOT NULL
                  AND (CAST(? AS jsonb) = '[]'::jsonb
                    OR jsonb_exists(CAST(? AS jsonb), meta_json ->> CAST(? AS text)))
                ORDER BY sector_code LIMIT ?
                """,
                        (rs, row) -> rs.getString("sector_code"),
                        "sectorLv" + level + "Code",
                        "sectorLv" + level + "Code",
                        filter,
                        filter,
                        "sectorLv" + level + "Code",
                        limit + 1));
    }

    /** One offset lookup for the bounded symbol set, rather than one lookup per child. */
    public Map<String, String> offsets(List<SymbolKeyProjection> symbols) {
        if (symbols.isEmpty()) return Map.of();
        List<Object> parameters = new ArrayList<>();
        parameters.add(parent.getJob().getId());
        symbols.forEach(symbol -> parameters.add(symbol.symbolKey()));
        String placeholders = String.join(",", Collections.nCopies(symbols.size(), "?"));
        Map<String, String> offsets = new HashMap<>();
        jdbc.query(
                """
                SELECT DISTINCT ON (meta_json ->> 'workKey') meta_json ->> 'workKey' AS work_key, new_offset
                FROM job_execution_histories WHERE job_id IN (
                    SELECT history.definition_id FROM job_definition_history_aliases history
                    JOIN job_definition_history_aliases requested ON history.retained_definition_id=requested.retained_definition_id
                    WHERE requested.definition_id = ?) AND new_offset IS NOT NULL
                  AND meta_json ->> 'workType' = 'SYMBOL' AND meta_json ->> 'workKey' IN (
                """
                        + placeholders
                        + ") ORDER BY meta_json ->> 'workKey', finished_at DESC, id DESC",
                rs -> {
                    offsets.put(rs.getString("work_key"), rs.getString("new_offset"));
                },
                parameters.toArray());
        return Map.copyOf(offsets);
    }

    private <T> List<T> bounded(List<T> values) {
        if (values.size() > limit)
            throw new IllegalArgumentException("JOB_RUN_WORK_BOUND_EXCEEDED");
        return values;
    }

    private record Symbol(String code, String exchange) implements SymbolKeyProjection {
        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getExchange() {
            return exchange;
        }
    }
}
