package com.omni.platform.modules.scheduler.dependencies;

import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;

import java.util.Optional;

/**
 * Logical partition readiness port. Active runtime uses PostgreSQL registered proofs.
 *
 * <p>Resolve dataset, partition and approved work identity without falling back to
 * legacy JSON discovery. Historical JSON implementations are direct test fixtures only.
 *
 * <p>The reader is expected to handle:
 * <ul>
 *   <li>Missing registered proof (return empty Optional)</li>
 *   <li>Read failures (propagate; never substitute another authority)</li>
 *   <li>Exact approved-version date coverage</li>
 * </ul>
 *
 * <p>Thread-safety: Implementations must be thread-safe for concurrent scheduler access.
 */
public interface ManifestReader {

    /**
     * Read a dataset manifest for the given dataset and partition.
     *
     * @param datasetRef logical reference to the dataset partition
     * @return manifest if found and readable, empty if not found
     * @throws ManifestReadException if the manifest exists but cannot be read or parsed
     */
    Optional<DatasetManifest> readManifest(DatasetRef datasetRef) throws ManifestReadException;

    /** Exact coverage for the SAME approved version; min/max timestamps are insufficient. */
    default boolean coversDate(DatasetRef ref, String version, java.time.LocalDate day) {
        return false;
    }

    /**
     * Check if a manifest exists without fully parsing it.
     *
     * <p>This is an optimization for EXISTS checks that don't need full manifest content.
     * Default implementation delegates to readManifest().isPresent().
     *
     * @param datasetRef logical reference to the dataset partition
     * @return true if the manifest file exists, false otherwise
     * @throws ManifestReadException if connectivity fails
     */
    default boolean manifestExists(DatasetRef datasetRef) throws ManifestReadException {
        return readManifest(datasetRef).isPresent();
    }
}
