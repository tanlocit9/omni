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
    @org.springframework.beans.factory.annotation.Autowired private OutputStorageHeads storageHeads;

    @org.springframework.beans.factory.annotation.Value(
            "${app.output-registration.versioned-storage-enabled:false}")
    private boolean versionedStorage;

    public OutputWorkReadiness(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public java.util.List<Map<String, Object>> read(String work) {
        return read(work, null);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public java.util.List<Map<String, Object>> read(String work, UUID attempt) {
        if (work == null || !work.matches("work:[0-9a-f]{64}"))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid atomicWorkId");
        var rows =
                jdbc.queryForList(
                        "SELECT p.*,p.payload::text AS output_json,e.meta_json->'approvedInputs' AS inputs "
                                + "FROM "
                                + (attempt == null
                                        ? "output_work_ready_catalog"
                                        : "output_work_ready_attempt_catalog")
                                + " p JOIN job_execution_histories e ON e.id=p.execution_id "
                                + "WHERE p.atomic_work_id=? "
                                + (attempt == null ? "" : "AND p.atomic_attempt_id=? ")
                                + "ORDER BY p.dataset,p.partition_values::text",
                        attempt == null ? new Object[] {work} : new Object[] {work, attempt});
        return rows.stream()
                .map(
                        row -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("atomicWorkId", work);
                            item.put("atomicAttemptId", row.get("atomic_attempt_id").toString());
                            item.put("businessDate", row.get("business_date").toString());
                            item.put("executionId", row.get("execution_id").toString());
                            item.put("dataVersion", row.get("data_version"));
                            Map<String, Object> output =
                                    new LinkedHashMap<>(
                                            json.readValue(
                                                    row.get("output_json").toString(), Map.class));
                            String coordinate =
                                    storageHeads == null
                                            ? (String) output.get("storageVersionId")
                                            : storageHeads.coordinate(
                                                    output.get("dataset").toString(),
                                                    output.get("partition"),
                                                    ((Number) row.get("generation")).longValue(),
                                                    (String) output.get("storageVersionId"));
                            if (versionedStorage && coordinate == null)
                                throw new org.springframework.web.server.ResponseStatusException(
                                        org.springframework.http.HttpStatus.CONFLICT,
                                        "STORAGE_BASELINE_REQUIRED");
                            if (coordinate != null) output.put("storageVersionId", coordinate);
                            item.put("output", output);
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
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM atomic_work_attempts target "
                                + "JOIN atomic_work_dependencies d ON d.attempt_id=target.id "
                                + "JOIN atomic_work_attempts upstream ON upstream.id=d.upstream_attempt_id "
                                + "JOIN job_execution_histories upstream_execution ON upstream_execution.id=upstream.execution_id "
                                + "LEFT JOIN output_completion_contracts declaration ON declaration.execution_id=upstream.execution_id "
                                + "WHERE target.execution_id=? AND declaration.execution_id IS NULL "
                                + "AND upstream_execution.meta_json->'workExpansion'->>'jobType' "
                                + "IN ('SYNC_STOCK_PRICE','SYNC_INDICATORS','SYNC_SIGNALS','EVALUATE_SIGNALS'))",
                        Boolean.class,
                        execution)))
            return null; // A missing declaration must not erase a frozen predecessor.
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
        boolean pinned =
                "VERSIONED"
                        .equals(
                                jdbc.queryForObject(
                                        "SELECT storage_policy FROM output_completion_contracts WHERE execution_id=?",
                                        String.class,
                                        execution));
        for (var entry : references.entrySet()) {
            var ref = entry.getKey();
            var rows =
                    jdbc.queryForList(
                            "SELECT p.atomic_work_id,p.business_date,p.atomic_attempt_id,p.data_version,p.generation,p.payload->>'storageVersionId' AS storage_version_id "
                                    + "FROM atomic_work_attempts target JOIN atomic_work_dependencies d ON d.attempt_id=target.id "
                                    + "JOIN atomic_work_attempts upstream ON upstream.id=d.upstream_attempt_id "
                                    + "JOIN output_completion_contracts declaration ON declaration.execution_id=upstream.execution_id "
                                    + "CROSS JOIN LATERAL jsonb_array_elements(declaration.required_outputs) expected "
                                    + "LEFT JOIN output_work_ready_attempt_catalog p ON p.atomic_attempt_id=d.upstream_attempt_id "
                                    + "AND p.dataset=expected->>'dataset' AND p.partition_values=expected->'partition' "
                                    + "WHERE target.execution_id=? AND expected->>'dataset'=? AND expected->'partition'=?::jsonb",
                            execution,
                            ref.getDataset(),
                            json.writeValueAsString(ref.getPartition()));
            if (rows.isEmpty())
                return null; // Every declared dependency needs a frozen READY upstream.
            for (var row : rows) {
                if (row.get("atomic_work_id") == null) return null;
                if (pinned
                        && (storageHeads == null
                                || storageHeads.coordinate(
                                                ref.getDataset(),
                                                ref.getPartition(),
                                                ((Number) row.get("generation")).longValue(),
                                                (String) row.get("storage_version_id"))
                                        == null)) return null;
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
