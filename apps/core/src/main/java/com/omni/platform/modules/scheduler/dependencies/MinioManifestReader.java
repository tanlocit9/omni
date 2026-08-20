package com.omni.platform.modules.scheduler.dependencies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.models.ColumnMetadata;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetInput;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MinIO-backed implementation of ManifestReader.
 * 
 * <p>Reads dataset manifests directly from MinIO object storage without caching.
 * For production use, wrap this with {@link CachedManifestReader} to reduce I/O.
 * 
 * <p>Path convention: {@code _metadata/datasets/{dataset}/{partition_path}/READY.json}
 * where partition_path is built from partition key-value pairs sorted by key.
 * 
 * <p>Thread-safety: This class is thread-safe. The MinioClient is thread-safe,
 * and the ObjectMapper is stateless.
 */
@Slf4j
public class MinioManifestReader implements ManifestReader {
    
    private final MinioClient minioClient;
    private final String bucketName;
    private final ObjectMapper objectMapper;
    
    public MinioManifestReader(MinioClient minioClient, String bucketName, ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Optional<DatasetManifest> readManifest(DatasetRef datasetRef) throws ManifestReadException {
        String objectPath = buildManifestPath(datasetRef);
        
        try {
            log.debug("Reading manifest from bucket={} path={}", bucketName, objectPath);
            
            InputStream stream = minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectPath)
                    .build()
            );
            
            DatasetManifest manifest = objectMapper.readerFor(DatasetManifest.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(stream);
            validateManifest(manifest, objectPath);
            log.debug("Successfully read manifest for dataset={} partition={}",
                datasetRef.getDataset(), datasetRef.getPartition());

            return Optional.of(manifest);

        } catch (ManifestReadException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw ManifestReadException.invalidJson(objectPath, e);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                log.debug("Manifest not found at path={}", objectPath);
                return Optional.empty();
            }
            throw ManifestReadException.ioError(objectPath, e);
            
        } catch (Exception e) {
            throw ManifestReadException.ioError(objectPath, e);
        }
    }
    
    @Override
    public boolean manifestExists(DatasetRef datasetRef) throws ManifestReadException {
        String objectPath = buildManifestPath(datasetRef);
        
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectPath)
                    .build()
            );
            return true;
            
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw ManifestReadException.ioError(objectPath, e);
            
        } catch (Exception e) {
            throw ManifestReadException.ioError(objectPath, e);
        }
    }
    
    /**
     * Build the manifest object path following the convention:
     * _metadata/datasets/{dataset}/{partition_path}/READY.json
     * 
     * <p>Partition path is built by:
     * 1. Sorting partition keys alphabetically
     * 2. Joining as key=value pairs with forward slashes
     * 3. If partition is empty, using "_default"
     * 
     * <p>Examples:
     * - dataset=eod, partition={exchange=hose} → _metadata/datasets/eod/exchange=hose/READY.json
     * - dataset=indicators, partition={} → _metadata/datasets/indicators/_default/READY.json
     * - dataset=signals, partition={exchange=hose, period=daily}
     *   → _metadata/datasets/signals/exchange=hose/period=daily/READY.json
     */
    String buildManifestPath(DatasetRef datasetRef) {
        String partitionPath;
        
        if (datasetRef.getPartition().isEmpty()) {
            partitionPath = "_default";
        } else {
            // Sort keys alphabetically for deterministic paths
            partitionPath = datasetRef.getPartition().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("/"));
        }
        
        return String.format(
            "_metadata/datasets/%s/%s/READY.json",
            datasetRef.getDataset(),
            partitionPath
        );
    }

    private void validateManifest(DatasetManifest manifest, String objectPath) {
        if (manifest.version() != 1) {
            throw ManifestReadException.unsupportedVersion(objectPath, manifest.version());
        }
        if (manifest.schemaVersion() != 1) {
            throw ManifestReadException.unsupportedSchemaVersion(
                objectPath,
                manifest.schemaVersion()
            );
        }
        if (!isSafeSegment(manifest.dataset())) {
            throw ManifestReadException.invalidContract(
                objectPath,
                "dataset must be a lowercase path-safe identifier"
            );
        }
        if (manifest.partition() == null) {
            throw ManifestReadException.invalidContract(objectPath, "partition is required");
        }
        manifest.partition().forEach((key, value) -> {
            if (!isSafeSegment(key) || !isSafeSegment(value)) {
                throw ManifestReadException.invalidContract(
                    objectPath,
                    "partition keys and values must be lowercase path-safe identifiers"
                );
            }
        });
        if (!isLogicalPath(manifest.path())) {
            throw ManifestReadException.invalidContract(
                objectPath,
                "path must be a relative traversal-safe logical data path"
            );
        }
        if (!"READY".equals(manifest.status())
                && !"PROCESSING".equals(manifest.status())
                && !"FAILED".equals(manifest.status())) {
            throw ManifestReadException.invalidContract(objectPath, "unsupported manifest status");
        }
        if (manifest.generatedAt() == null || manifest.generatedAt().isBlank()) {
            throw ManifestReadException.invalidContract(objectPath, "generatedAt is required");
        }
        if (manifest.objectCount() < 0 || manifest.totalBytes() < 0
                || manifest.rowCount() < 0 || manifest.columnCount() < 0) {
            throw ManifestReadException.invalidContract(
                objectPath,
                "counts and totalBytes must be non-negative"
            );
        }
        if (manifest.columns() == null || manifest.columnCount() != manifest.columns().size()) {
            throw ManifestReadException.invalidContract(
                objectPath,
                "columnCount must equal columns size"
            );
        }
        for (ColumnMetadata column : manifest.columns()) {
            if (column == null || column.name() == null || column.name().isBlank()
                    || column.type() == null || column.type().isBlank()) {
                throw ManifestReadException.invalidContract(
                    objectPath,
                    "columns require non-empty name and type"
                );
            }
        }
        if (manifest.inputs() == null) {
            throw ManifestReadException.invalidContract(objectPath, "inputs is required");
        }
        for (DatasetInput input : manifest.inputs()) {
            if (input == null || !isSafeSegment(input.dataset()) || input.partition() == null
                    || !isSha256(input.dataVersion())) {
                throw ManifestReadException.invalidContract(
                    objectPath,
                    "inputs require a safe dataset, partition, and valid dataVersion"
                );
            }
            input.partition().forEach((key, value) -> {
                if (!isSafeSegment(key) || !isSafeSegment(value)) {
                    throw ManifestReadException.invalidContract(
                        objectPath,
                        "input partition keys and values must be lowercase path-safe identifiers"
                    );
                }
            });
        }
        if (manifest.isReady()) {
            if (manifest.objectCount() < 1) {
                throw ManifestReadException.invalidContract(
                    objectPath,
                    "READY manifest requires objectCount >= 1"
                );
            }
            if (!isSha256(manifest.dataVersion())) {
                throw ManifestReadException.invalidContract(
                    objectPath,
                    "READY manifest requires a valid dataVersion"
                );
            }
            if (!isSha256(manifest.schemaHash())) {
                throw ManifestReadException.invalidContract(
                    objectPath,
                    "READY manifest requires a valid schemaHash"
                );
            }
        }
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private boolean isSafeSegment(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._-]*");
    }

    private boolean isLogicalPath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/")) {
            return false;
        }
        return java.util.Arrays.stream(value.split("/", -1)).noneMatch(".."::equals);
    }
}
