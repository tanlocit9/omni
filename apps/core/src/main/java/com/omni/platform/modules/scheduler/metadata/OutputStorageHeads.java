package com.omni.platform.modules.scheduler.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Immutable physical write bases; backend versions never replace logical work identity. */
@Service
public class OutputStorageHeads {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutputStorageHeads(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void freeze(UUID execution) {
        var contracts =
                jdbc.queryForList(
                        "SELECT storage_policy,required_outputs::text FROM output_completion_contracts WHERE execution_id=? FOR UPDATE",
                        execution);
        if (contracts.isEmpty() || !"VERSIONED".equals(contracts.getFirst().get("storage_policy")))
            return;
        if (!read(execution).isEmpty()) return;
        List<Map<String, Object>> bases = new ArrayList<>();
        for (var output : json.readTree(contracts.getFirst().get("required_outputs").toString())) {
            String dataset = output.get("dataset").asText();
            String partition = output.get("partition").toString();
            var heads =
                    jdbc.queryForList(
                            "SELECT storage_version_id,generation FROM output_storage_heads WHERE dataset=? AND partition_values=?::jsonb FOR SHARE",
                            dataset,
                            partition);
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("dataset", dataset);
            base.put("partition", json.readValue(partition, Map.class));
            base.put(
                    "storageVersionId",
                    heads.isEmpty() ? null : heads.getFirst().get("storage_version_id"));
            base.put("generation", heads.isEmpty() ? null : heads.getFirst().get("generation"));
            bases.add(base);
        }
        jdbc.update(
                "INSERT INTO output_storage_write_bases(execution_id,bases) VALUES (?,?::jsonb)",
                execution,
                json.writeValueAsString(bases));
    }

    public List<Map<String, Object>> read(UUID execution) {
        var rows =
                jdbc.queryForList(
                        "SELECT bases::text FROM output_storage_write_bases WHERE execution_id=?",
                        execution);
        return rows.isEmpty()
                ? List.of()
                : json.readValue(rows.getFirst().get("bases").toString(), List.class);
    }

    /** Only called inside the READY transaction after SUCCESS, under the partition lock. */
    public boolean promote(
            UUID execution, String dataset, String partition, long generation, String version) {
        if (version == null) {
            return !Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM output_storage_heads WHERE dataset=? AND partition_values=?::jsonb)",
                            Boolean.class,
                            dataset,
                            partition));
        }
        jdbc.update(
                "INSERT INTO output_storage_heads(dataset,partition_values,storage_version_id,generation,source_execution_id) "
                        + "VALUES (?,?::jsonb,?,?,?) ON CONFLICT(dataset,partition_values) DO UPDATE SET "
                        + "storage_version_id=excluded.storage_version_id,generation=excluded.generation,"
                        + "source_execution_id=excluded.source_execution_id,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE output_storage_heads.generation<excluded.generation",
                dataset,
                partition,
                version,
                generation,
                execution);
        return true;
    }

    /** Old proofs may use only the immutable historical baseline, never today's head. */
    public String coordinate(String dataset, Object partition, long generation, String registered) {
        if (registered != null) return registered;
        var rows =
                jdbc.queryForList(
                        "SELECT baseline_version_id FROM output_storage_heads WHERE dataset=? "
                                + "AND partition_values=?::jsonb AND baseline_generation>=?",
                        dataset,
                        json.writeValueAsString(partition),
                        generation);
        return rows.isEmpty() ? null : (String) rows.getFirst().get("baseline_version_id");
    }
}
