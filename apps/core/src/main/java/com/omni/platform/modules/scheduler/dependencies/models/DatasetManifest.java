package com.omni.platform.modules.scheduler.dependencies.models;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Dataset metadata manifest stored in object storage.
 * <p>
 * Provides dataset readiness, schema, statistics, and lineage information
 * without requiring full Parquet scan. Published after successful data
 * validation to guarantee READY-last semantics.
 * <p>
 * Path: {@code _metadata/datasets/{dataset}/{partition_path}/READY.json}
 */
public record DatasetManifest(
        int version,
        String dataset,
        Map<String, String> partition,
        String status,                      // "READY", "PROCESSING", "FAILED"
        String dataVersion,                 // "sha256:..."
        String path,                        // Parquet data path
        int objectCount,
        long totalBytes,
        long rowCount,
        int columnCount,
        List<ColumnMetadata> columns,
        int schemaVersion,
        String schemaHash,
        String minTimestamp,                // nullable - ISO 8601 UTC
        String maxTimestamp,                // nullable - ISO 8601 UTC
        List<DatasetInput> inputs,          // nullable - upstream lineage
        String sourceExecutionId,           // nullable - job execution ID
        String generatedAt                  // ISO 8601 UTC
) {
    /**
     * Dataset is ready for consumption.
     */
    public boolean isReady() {
        return "READY".equals(status);
    }

    /**
     * Dataset is currently being written/validated.
     */
    public boolean isProcessing() {
        return "PROCESSING".equals(status);
    }

    /**
     * Dataset write or validation failed.
     */
    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    /**
     * Check if manifest has lineage information.
     */
    public boolean hasLineage() {
        return inputs != null && !inputs.isEmpty();
    }
}
