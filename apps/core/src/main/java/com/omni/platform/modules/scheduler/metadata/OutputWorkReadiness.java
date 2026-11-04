package com.omni.platform.modules.scheduler.metadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import tools.jackson.databind.json.JsonMapper;

/** Resolve only the graph's frozen upstream attempts, not the latest work sharing a file. */
@Service
public class OutputWorkReadiness {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutputWorkReadiness(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public java.util.List<Map<String, Object>> read(String work) {
        if (work == null || !work.matches("work:[0-9a-f]{64}"))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid atomicWorkId");
        var rows =
                jdbc.queryForList(
                        "SELECT p.*,p.payload::text AS output_json,e.meta_json->'approvedInputs' AS inputs "
                                + "FROM output_work_ready_catalog p JOIN job_execution_histories e ON e.id=p.execution_id "
                                + "WHERE p.atomic_work_id=? ORDER BY p.dataset,p.partition_values::text",
                        work);
        return rows.stream()
                .map(
                        row -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("atomicWorkId", work);
                            item.put("atomicAttemptId", row.get("atomic_attempt_id").toString());
                            item.put("businessDate", row.get("business_date").toString());
                            item.put("executionId", row.get("execution_id").toString());
                            item.put("dataVersion", row.get("data_version"));
                            item.put(
                                    "output",
                                    json.readValue(row.get("output_json").toString(), Map.class));
                            item.put(
                                    "approvedInputs",
                                    row.get("inputs") == null
                                            ? java.util.List.of()
                                            : json.readValue(
                                                    row.get("inputs").toString(),
                                                    java.util.List.class));
                            item.put(
                                    "upstreamWorks",
                                    jdbc.queryForList(
                                            "SELECT u.work_id AS \"atomicWorkId\",u.id AS \"atomicAttemptId\" "
                                                    + "FROM atomic_work_dependencies d JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id "
                                                    + "WHERE d.attempt_id=? ORDER BY u.work_id,u.id",
                                            row.get("atomic_attempt_id")));
                            return item;
                        })
                .toList();
    }

    public boolean enrolled(UUID execution) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM output_completion_contracts WHERE execution_id=?)",
                        Boolean.class,
                        execution));
    }

    @Transactional(readOnly = true)
    public Map<DatasetRef, String> bind(UUID execution, Map<DatasetRef, String> versions) {
        if (!Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM output_completion_contracts WHERE execution_id=?)",
                        Boolean.class,
                        execution)))
            return versions; // Explicit legacy boundary, never a fallback for enrolled work.
        Map<DatasetRef, String> references = new LinkedHashMap<>(versions);
        for (var row :
                jdbc.queryForList(
                        "SELECT DISTINCT expected->>'dataset' AS dataset,(expected->'partition')::text AS partition "
                                + "FROM atomic_work_attempts target JOIN atomic_work_dependencies d ON d.attempt_id=target.id "
                                + "JOIN atomic_work_attempts upstream ON upstream.id=d.upstream_attempt_id "
                                + "JOIN output_completion_contracts declaration ON declaration.execution_id=upstream.execution_id "
                                + "CROSS JOIN LATERAL jsonb_array_elements(declaration.required_outputs) expected WHERE target.execution_id=?",
                        execution))
            references.put(
                    DatasetRef.of(
                            row.get("dataset").toString(),
                            json.readValue(row.get("partition").toString(), Map.class)),
                    "work-scope");
        Map<DatasetRef, String> bindings = new LinkedHashMap<>();
        for (var entry : references.entrySet()) {
            var ref = entry.getKey();
            var rows =
                    jdbc.queryForList(
                            "SELECT p.atomic_work_id,p.business_date,p.atomic_attempt_id,p.data_version "
                                    + "FROM atomic_work_attempts target JOIN atomic_work_dependencies d ON d.attempt_id=target.id "
                                    + "JOIN atomic_work_attempts upstream ON upstream.id=d.upstream_attempt_id "
                                    + "JOIN output_completion_contracts declaration ON declaration.execution_id=upstream.execution_id "
                                    + "CROSS JOIN LATERAL jsonb_array_elements(declaration.required_outputs) expected "
                                    + "LEFT JOIN output_work_ready_catalog p ON p.atomic_attempt_id=d.upstream_attempt_id "
                                    + "AND p.dataset=expected->>'dataset' AND p.partition_values=expected->'partition' "
                                    + "WHERE target.execution_id=? AND expected->>'dataset'=? AND expected->'partition'=?::jsonb",
                            execution,
                            ref.getDataset(),
                            json.writeValueAsString(ref.getPartition()));
            if (rows.isEmpty())
                return null; // Every declared dependency needs a frozen READY upstream.
            for (var row : rows) {
                if (row.get("atomic_work_id") == null) return null;
                var bound =
                        ref.bindWork(
                                row.get("atomic_work_id").toString(),
                                java.time.LocalDate.parse(row.get("business_date").toString()),
                                UUID.fromString(row.get("atomic_attempt_id").toString()));
                if (bindings.putIfAbsent(bound, row.get("data_version").toString()) != null)
                    return null;
            }
        }
        return Map.copyOf(bindings);
    }
}
