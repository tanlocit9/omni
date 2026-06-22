package com.omni.platform.shared.infrastructure.minio.adapters;

import java.io.InputStream;

import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.omni.platform.shared.enums.StorageProvider;
import com.omni.platform.shared.infrastructure.adapters.AbstractStorageAdapter;
import com.omni.platform.shared.ports.DeletablePort;
import com.omni.platform.shared.ports.ReadablePort;
import com.omni.platform.shared.ports.WritablePort;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@ConditionalOnProperty(
        prefix = "min-io",
        name = "enabled",
        havingValue = "true"
)
@AllArgsConstructor
public class MinioStorageAdapter extends AbstractStorageAdapter implements DeletablePort, WritablePort, ReadablePort {

    private final MinioClient minioClient;

    @Override
    protected void doValidate() throws Exception {
        // Simple call to check if the server is reachable and credentials are valid
        minioClient.listBuckets();
        log.info("Successfully connected to MinIO server [Provider: {}]", getProvider());
    }

    @Override
    protected Logger getLog() {
        return log;
    }

    @Override
    public StorageProvider getProvider() {
        return StorageProvider.MINIO;
    }

    @Override
    public void delete(String folderName, String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(folderName)
                            .object(objectName)
                            .build()
            );
            log.info("Successfully deleted object: {} from bucket: {}", objectName, folderName);
        } catch (Exception e) {
            log.error("Failed to delete object: {} from bucket: {}. Error: {}", objectName, folderName, e.getMessage());
            throw new RuntimeException("Error during MinIO delete operation", e);
        }
    }

    @Override
    public InputStream read(String folderName, String objectName) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(folderName)
                            .object(objectName)
                            .build()
            );
        } catch (Exception e) {
            log.error("Failed to read object: {} from bucket: {}. Error: {}", objectName, folderName, e.getMessage());
            throw new RuntimeException("Error during MinIO read operation", e);
        }
    }

    @Override
    public void write(String folderName, String objectName, InputStream data, String contentType) {
        try {
            // Note: For unknown stream size, we use -1 for size and 10MiB for partSize
            // MinIO client will buffer data if size is unknown
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(folderName)
                            .object(objectName)
                            .contentType(contentType)
                            .stream(data, -1, 10485760) // 10MiB part size for unknown stream length
                            .build()
            );
            log.info("Successfully uploaded object: {} to bucket: {}", objectName, folderName);
        } catch (Exception e) {
            log.error("Failed to upload object: {} to bucket: {}. Error: {}", objectName, folderName, e.getMessage());
            throw new RuntimeException("Error during MinIO upload operation", e);
        }
    }
}