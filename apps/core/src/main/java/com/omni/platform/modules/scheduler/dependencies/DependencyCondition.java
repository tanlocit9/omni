package com.omni.platform.modules.scheduler.dependencies;

/**
 * Conditions that can be checked against dataset manifests.
 * <p>
 * V1 supports a small explicit set of conditions. Future versions may
 * add more sophisticated checks (e.g. schema validation, freshness windows).
 * <p>
 * Evaluation order for typical dependency checks:
 * <ol>
 * <li>EXISTS - manifest file exists in object storage</li>
 * <li>READY - manifest status is "READY"</li>
 * <li>MIN_ROW_COUNT - dataset has sufficient rows</li>
 * <li>CURRENT_INPUTS - downstream uses current upstream dataVersion</li>
 * </ol>
 */
public enum DependencyCondition {

    /**
     * Dataset manifest exists in object storage.
     * <p>
     * This is the most basic check - the dataset partition has been written
     * at least once. Does not guarantee the data is valid or current.
     */
    EXISTS,

    /**
     * Dataset manifest status is "READY".
     * <p>
     * Guarantees that:
     * <ul>
     * <li>Parquet data was written successfully</li>
     * <li>Data passed validation checks</li>
     * <li>Manifest was published last (READY-last semantics)</li>
     * </ul>
     */
    READY,

    /**
     * Dataset partition matches expected partition coordinates.
     * <p>
     * Verifies that the manifest partition field matches the requested
     * partition exactly. Useful for date-based workflows where you need
     * to confirm the exact date partition exists.
     */
    PARTITION_MATCH,

    /**
     * Dataset has minimum row count.
     * <p>
     * Checks manifest.rowCount >= threshold. Useful for detecting
     * empty or truncated datasets that would cause downstream failures.
     */
    MIN_ROW_COUNT,

    /**
     * Dataset schema version is supported by consumer.
     * <p>
     * Checks manifest.schemaVersion against consumer's expected range.
     * Prevents downstream jobs from processing incompatible schema versions.
     */
    SUPPORTED_SCHEMA_VERSION,

    /**
     * Dataset is not stale (within maximum freshness lag).
     * <p>
     * Compares manifest.generatedAt against current time to ensure
     * the dataset is fresh enough for time-sensitive operations.
     */
    MAX_FRESHNESS_LAG,

    /**
     * Downstream dataset was built from current upstream versions.
     * <p>
     * Compares downstream manifest.inputs[].dataVersion against current
     * upstream manifest.dataVersion to detect stale lineage.
     * <p>
     * Example: If EOD has dataVersion B but Indicators.inputs[eod] = A,
     * then Indicators is physically READY but logically STALE.
     */
    CURRENT_INPUTS
}
