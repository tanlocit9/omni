package com.omnistorage.storage.application.usecases;

import com.omnistorage.storage.application.dtos.FileDeleteResult;
import com.omnistorage.storage.application.dtos.FileUploadResult;
import com.omnistorage.storage.core.configs.StorageProviderRegistry;
import com.omnistorage.storage.core.enums.StorageProvider;
import com.omnistorage.storage.core.ports.DeletablePort;
import com.omnistorage.storage.core.ports.ReadablePort;
import com.omnistorage.storage.core.ports.WritablePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUseCaseService implements FileUseCase {
    private final StorageProviderRegistry registry;

    @Override
    public List<FileUploadResult> uploadBulk(StorageProvider provider, String bucket, MultipartFile[] files) {
        var writer = registry.getPort(provider, WritablePort.class);

        // Use Virtual Threads to handle parallel upload
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileUploadResult>> futures = Arrays.stream(files).map(file ->
                    executor.submit(() -> {
                        try {
                            writer.write(bucket, file.getOriginalFilename(), file.getInputStream(), file.getContentType());
                            return new FileUploadResult(file.getOriginalFilename(), true, "Success");
                        } catch (Exception e) {
                            return new FileUploadResult(file.getOriginalFilename(), false, e.getMessage());
                        }
                    })
            ).toList();

            return futures.stream().map(this::waitForFuture).toList();
        }
    }

    @Override
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
            throw new RuntimeException("Failed to create ZIP archive", e);
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
