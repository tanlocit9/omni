package com.omni.platform.modules.scheduler.dependencies;

/**
 * High-level dependency check status.
 * <p>
 * Maps to scheduler decisions:
 * <ul>
 * <li>READY → claim job, create execution, dispatch</li>
 * <li>All others → defer job, log blocking reason, retry later</li>
 * </ul>
 */
public enum DependencyStatus {

    /**
     * All dependency conditions satisfied - job can execute.
     */
    READY,

    /**
     * Required dataset manifest does not exist.
     * <p>
     * The upstream dataset has never been written or the partition
     * is missing. Wait for upstream job to complete.
     */
    MISSING,

    /**
     * Dataset manifest exists but status is PROCESSING or FAILED.
     * <p>
     * The dataset is being written or failed validation. Wait for
     * upstream job to reach READY state.
     */
    NOT_READY,

    /**
     * Dataset is READY but data is stale relative to current inputs.
     * <p>
     * The downstream dataset was built from an old upstream version.
     * Wait for downstream to rebuild with current inputs.
     */
    STALE,

    /**
     * Dataset exists but is empty or below minimum row threshold.
     * <p>
     * The dataset passed basic validation but has insufficient data
     * for meaningful downstream processing.
     */
    EMPTY,

    /**
     * Dataset schema version is incompatible with consumer.
     * <p>
     * The dataset exists and is READY but the schema version is too
     * old or too new for the consumer to process safely.
     */
    INVALID_SCHEMA,

    /**
     * Downstream dataset's input versions don't match current upstream.
     * <p>
     * Similar to STALE but specifically tracks dataVersion mismatches
     * in the manifest lineage chain.
     */
    INPUT_VERSION_MISMATCH,

    /**
     * Dependency check failed due to I/O or system error.
     * <p>
     * The manifest could not be read from object storage. This is
     * different from MISSING (manifest doesn't exist) - this indicates
     * a transient failure that should be retried.
     */
    ERROR
}
