package com.omni.platform.modules.scheduler.dependencies.models;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Internal logical view of one partition selected from the global metadata document.
 *
 * <p>The record is not persisted independently. It provides existing dependency
 * evaluators with readiness, schema, statistics, lineage, and trusted backend path data.
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
