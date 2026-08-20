package com.omni.platform.modules.scheduler.dependencies;

import java.util.Optional;

import lombok.Getter;

/**
 * Result of checking dataset dependency conditions.
 * <p>
 * Encapsulates both the readiness state and the reason for blocking.
 * This allows the scheduler to defer jobs without creating false
 * failure history entries.
 */
@Getter
public final class DependencyCheckResult {

    private final DependencyStatus status;
    private final String reason;
    private final DatasetRef datasetRef;

    private DependencyCheckResult(DependencyStatus status, String reason, DatasetRef datasetRef) {
        this.status = status;
        this.reason = reason;
        this.datasetRef = datasetRef;
    }

    /**
     * All dependencies satisfied - job is ready to execute.
     */
    public static DependencyCheckResult ready() {
        return new DependencyCheckResult(DependencyStatus.READY, null, null);
    }

    /**
     * Dataset manifest does not exist.
     */
    public static DependencyCheckResult missing(DatasetRef ref) {
        return new DependencyCheckResult(
                DependencyStatus.MISSING,
                String.format("Dataset manifest does not exist: %s", ref),
                ref);
    }

    /**
     * Dataset manifest exists but status is not READY.
     */
    public static DependencyCheckResult notReady(DatasetRef ref, String manifestStatus) {
        return new DependencyCheckResult(
                DependencyStatus.NOT_READY,
                String.format("Dataset %s status is %s, expected READY", ref, manifestStatus),
                ref);
    }

    /**
     * Dataset exists and is READY but data is stale relative to upstream.
     */
    public static DependencyCheckResult stale(DatasetRef ref, String detail) {
        return new DependencyCheckResult(
                DependencyStatus.STALE,
                String.format("Dataset %s is stale: %s", ref, detail),
                ref);
    }

    /**
     * Dataset is empty or below minimum row count threshold.
     */
    public static DependencyCheckResult empty(DatasetRef ref, long rowCount, long minRows) {
        return new DependencyCheckResult(
                DependencyStatus.EMPTY,
                String.format("Dataset %s has %d rows, minimum required: %d", ref, rowCount, minRows),
                ref);
    }

    /**
     * Dataset schema version is not supported by consumer.
     */
    public static DependencyCheckResult invalidSchema(DatasetRef ref, int schemaVersion, String supportedRange) {
        return new DependencyCheckResult(
                DependencyStatus.INVALID_SCHEMA,
                String.format("Dataset %s schema version %d not in supported range: %s",
                        ref, schemaVersion, supportedRange),
                ref);
    }

    /**
     * Downstream dataset's input versions don't match current upstream versions.
     */
    public static DependencyCheckResult inputVersionMismatch(DatasetRef ref, String upstreamDataset,
            String expectedVersion, String actualVersion) {
        return new DependencyCheckResult(
                DependencyStatus.INPUT_VERSION_MISMATCH,
                String.format("Dataset %s input %s version mismatch: expected %s, actual %s",
                        ref, upstreamDataset, expectedVersion, actualVersion),
                ref);
    }

    /**
     * Downstream dataset's input versions don't match current upstream versions.
     * Overload that accepts upstream DatasetRef for better type safety.
     */
    public static DependencyCheckResult inputVersionMismatch(DatasetRef downstreamRef, DatasetRef upstreamRef,
            String recordedVersion, String currentVersion) {
        return new DependencyCheckResult(
                DependencyStatus.INPUT_VERSION_MISMATCH,
                String.format("Dataset %s input %s version mismatch: recorded %s, current %s",
                        downstreamRef, upstreamRef, recordedVersion, currentVersion),
                downstreamRef);
    }

    /**
     * Manifest read failed due to I/O error.
     */
    public static DependencyCheckResult error(DatasetRef ref, String errorMessage) {
        return new DependencyCheckResult(
                DependencyStatus.ERROR,
                String.format("Failed to check dataset %s: %s", ref, errorMessage),
                ref);
    }

    public boolean isReady() {
        return status == DependencyStatus.READY;
    }

    public boolean isBlocked() {
        return status != DependencyStatus.READY;
    }

    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    @Override
    public String toString() {
        if (status == DependencyStatus.READY) {
            return "READY";
        }
        return String.format("%s: %s", status, reason);
    }
}
