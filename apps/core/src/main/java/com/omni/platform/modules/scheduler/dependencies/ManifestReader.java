package com.omni.platform.modules.scheduler.dependencies;

import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;

import java.util.Optional;

/**
 * Reader interface for dataset metadata manifests stored in object storage.
 * 
 * <p>Implementations fetch mutable READY pointers from S3/MinIO paths following the convention:
 * {@code _metadata/datasets/{dataset}/{partition_path}/READY.json}
 * 
 * <p>The reader is expected to handle:
 * <ul>
 *   <li>Missing manifests (return empty Optional)</li>
 *   <li>I/O errors (throw ManifestReadException)</li>
 *   <li>Malformed JSON (throw ManifestReadException)</li>
 *   <li>Caching for performance (implementation-specific)</li>
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
