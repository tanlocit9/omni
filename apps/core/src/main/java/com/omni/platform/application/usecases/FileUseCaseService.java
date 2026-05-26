package com.omni.platform.application.usecases;

import com.omni.platform.application.dtos.FileDeleteResult;
import com.omni.platform.application.dtos.FileUploadResult;
import com.omni.platform.core.enums.StorageProvider;
import com.omni.platform.core.events.FileUploadedEvent;
import com.omni.platform.core.exceptions.file.FileDownloadFailedException;
import com.omni.platform.core.ports.DeletablePort;
import com.omni.platform.core.ports.ReadablePort;
import com.omni.platform.core.ports.WritablePort;
import com.omni.platform.infrastructure.storages.StorageProviderRegistry;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUseCaseService implements FileUseCase {
    private final StorageProviderRegistry registry;

    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public List<FileUploadResult> uploadBulk(StorageProvider provider, String bucket, MultipartFile[] files) {
        var writer = registry.getPort(provider, WritablePort.class);

        log.info("Start uploading");

        // Step 1: Upload files in parallel
        List<FileUploadResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileUploadResult>> futures = Arrays.stream(files).map(file ->
                    executor.submit(() -> {
                        try {
                            var fileId = String.valueOf(UUID.randomUUID());
                            writer.write(bucket, fileId, file.getInputStream(), file.getContentType());
                            // Return result with fileId for event publishing later
                            return new FileUploadResult(
                                    file.getOriginalFilename(),
                                    fileId,  // Add fileId to result
                                    file.getContentType(),
                                    true,
                                    "Success"
                            );
                        } catch (Exception e) {
                            return new FileUploadResult(file.getOriginalFilename(), null, null, false, e.getMessage());
                        }
                    })
            ).toList();

            results = futures.stream().map(this::waitForFuture).toList();
        }

        // Step 2: Publish events in main thread (inside transaction)
        results.stream()
                .filter(FileUploadResult::success)
                .forEach(result -> eventPublisher.publishEvent(
                        new FileUploadedEvent(
                                result.fileId(),
                                bucket,
                                result.contentType(),
                                provider
                        )
                ));

        log.info("Published {} events", results.stream().filter(FileUploadResult::success).count());
        return results;
    }

    @Override
    @Transactional
    public void downloadBulkAsZip(StorageProvider provider, String bucket, List<String> fileNames, OutputStream outputStream) {
        var reader = registry.getPort(provider, ReadablePort.class);

        try (ZipOutputStream zos = new ZipOutputStream(outputStream)) {
            for (String fileName : fileNames) {
                try (InputStream is = reader.read(bucket, fileName)) {
                    ZipEntry zipEntry = new ZipEntry(fileName);
                    zos.putNextEntry(zipEntry);
                    is.transferTo(zos); // Java 9+ stream transfer
                    zos.closeEntry();
                } catch (Exception e) {
                    log.error("Error adding file {} to zip: {}", fileName, e.getMessage());
                    // Skip if error
                }
            }
            zos.finish();
        } catch (IOException e) {
            log.error("Failed to create ZIP archive: {}", e.getMessage());
            throw new FileDownloadFailedException("Failed to create ZIP archive");
        }
    }

    @Override
    public List<FileDeleteResult> deleteBulk(StorageProvider provider, String bucket, List<String> fileNames) {
        var deleter = registry.getPort(provider, DeletablePort.class);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileDeleteResult>> futures = fileNames.stream().map(fileName ->
                    executor.submit(() -> {
                        try {
                            deleter.delete(bucket, fileName);
                            return new FileDeleteResult(fileName, true, "Deleted");
                        } catch (Exception e) {
                            return new FileDeleteResult(fileName, false, e.getMessage());
                        }
                    })
            ).toList();

            return futures.stream().map(this::waitForFuture).toList();
        }
    }

    private <T> T waitForFuture(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new RuntimeException("Thread execution failed", e);
        }
    }
}
