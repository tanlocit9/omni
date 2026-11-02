package com.omni.platform.modules.scheduler.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.models.ColumnMetadata;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetInput;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import tools.jackson.databind.json.JsonMapper;

/** Snapshot-backed reads only. No manifest fallback and no mutable physical paths. */
@Service
public class OutputReadinessProvider {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Environment environment;

    public boolean coversDate(DatasetRef ref, String version, java.time.LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM output_ready_catalog p "
                + "WHERE p.dataset=? AND p.partition_values=?::jsonb AND p.payload->>'dataVersion'=? "
                + "AND p.payload->'coverageDates' ?? ?)",
                Boolean.class, ref.getDataset(), json.writeValueAsString(ref.getPartition()), version, day.toString()));
    }

    public OutputReadinessProvider(JdbcTemplate jdbc, JsonMapper json, Environment environment) {
        this.jdbc = jdbc; this.json = json; this.environment = environment;
    }

    @Transactional(readOnly = true, timeout = 10)
    public Optional<DatasetManifest> readManifest(DatasetRef ref) {
        var rows = jdbc.queryForList("SELECT payload::text,execution_id,promoted_at FROM output_ready_catalog p "
                + "WHERE p.dataset=? AND p.partition_values=?::jsonb",
                ref.getDataset(), json.writeValueAsString(ref.getPartition()));
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.getFirst();
        var output = json.readValue(row.get("payload").toString(), OutputRegistrationEvent.Output.class);
        return Optional.of(new DatasetManifest(1, output.dataset(), output.partition(), "READY", output.dataVersion(),
                snapshotPath(output), 1, output.totalBytes(), output.rowCount(), output.columns().size(),
                output.columns().stream().map(c -> new ColumnMetadata(c.name(), c.type(), c.nullable())).toList(),
                output.schemaVersion(), output.schemaHash(), timestampBound(output,false), timestampBound(output,true),
                output.inputs().stream().map(i -> new DatasetInput(i.dataset(), i.partition(), i.dataVersion())).toList(),
                row.get("execution_id") == null ? null : row.get("execution_id").toString(),
                ((java.sql.Timestamp)row.get("promoted_at")).toInstant().toString()));
    }

    @Transactional(readOnly = true, timeout = 10,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> page(int offset, int limit, Long revision) {
        if (offset < 0 || offset > 100000 || limit < 1 || limit > 200)
            throw new IllegalArgumentException("Invalid readiness page bounds");
        long current = jdbc.queryForObject("SELECT revision FROM output_ready_revision WHERE id=1", Long.class);
        if (revision != null && revision != current)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "READY changed while paging; restart the read");
        var rows = jdbc.queryForList("SELECT payload::text,execution_id,promoted_at FROM output_ready_catalog p "
                + "ORDER BY p.dataset,p.partition_values::text LIMIT ? OFFSET ?",
                limit + 1, offset);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var row : rows.stream().limit(limit).toList()) {
            var output = json.readValue(row.get("payload").toString(), OutputRegistrationEvent.Output.class);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("values", output.partition()); manifest.put("status", "READY");
            manifest.put("path", snapshotPath(output)); manifest.put("canonicalPath", canonicalPath(output));
            manifest.put("dataVersion", output.dataVersion()); manifest.put("schemaVersion", output.schemaVersion());
            manifest.put("schemaHash", output.schemaHash()); manifest.put("objectCount", 1);
            manifest.put("totalBytes", output.totalBytes()); manifest.put("rowCount", output.rowCount());
            manifest.put("columnCount", output.columns().size()); manifest.put("columns", output.columns());
            manifest.put("inputs", output.inputs()); manifest.put("coverageDates", output.coverageDates());
            manifest.put("minTimestamp", timestampBound(output,false)); manifest.put("maxTimestamp", timestampBound(output,true));
            manifest.put("generatedAt", ((java.sql.Timestamp)row.get("promoted_at")).toInstant().toString());
            manifest.put("sourceExecutionId", row.get("execution_id") == null ? null : row.get("execution_id").toString());
            items.add(Map.of("dataset", output.dataset(), "manifest", manifest));
        }
        return Map.of("revision", current, "items", items, "hasMore", rows.size() > limit);
    }

    private String canonicalPath(OutputRegistrationEvent.Output output) {
        String fallback = switch (output.dataset()) {
            case "eod" -> "{exchange}/{code}.parquet";
            case "indicators" -> "{source}/{timeframe}/{exchange}/{code}.parquet";
            case "signals" -> "{strategy}/{timeframe}/{exchange}.parquet";
            default -> throw new IllegalArgumentException("Dataset has no approved snapshot route");
        };
        String prefix = "stock-data.paths." + output.dataset();
        return render(environment.getProperty(prefix + ".base", output.dataset() + "/")
                + environment.getProperty(prefix + ".pattern", fallback), output.partition());
    }

    private static String timestampBound(OutputRegistrationEvent.Output output, boolean maximum) {
        if (output.dataset().equals("signals")) return null;
        return (maximum ? output.coverageDates().getLast() : output.coverageDates().getFirst()) + "T00:00:00";
    }

    private String snapshotPath(OutputRegistrationEvent.Output output) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    WorkExpansion.canonical(output.partition()).getBytes(StandardCharsets.UTF_8)));
            return render(environment.getProperty("stock-data.paths.output-snapshots.base", "_output-snapshots/")
                    + environment.getProperty("stock-data.paths.output-snapshots.pattern", "{dataset}/{partition_hash}/{data_version}.parquet"),
                    Map.of("dataset", output.dataset(), "partition_hash", hash, "data_version", output.dataVersion().substring(7)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String render(String template, Map<String, String> values) {
        for (var value : values.entrySet()) template = template.replace("{" + value.getKey() + "}", value.getValue());
        if (template.startsWith("/") || template.contains(":") || template.contains("\\") || template.contains("{")
                || java.util.Arrays.stream(template.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
            throw new IllegalArgumentException("Unsafe output path configuration");
        return template;
    }
}
