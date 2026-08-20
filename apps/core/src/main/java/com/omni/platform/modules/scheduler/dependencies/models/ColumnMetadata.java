package com.omni.platform.modules.scheduler.dependencies.models;

/**
 * Dataset column metadata extracted from DataFrame schema.
 * <p>
 * Maps pandas dtypes to SQL-like type names for cross-language compatibility.
 */
public record ColumnMetadata(
        String name,
        String type,        // "BIGINT", "DOUBLE", "VARCHAR", "TIMESTAMP", "BOOLEAN"
        boolean nullable
) {
}
