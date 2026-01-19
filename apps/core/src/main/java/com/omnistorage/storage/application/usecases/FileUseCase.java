package com.omnistorage.storage.application.usecases;

import com.omnistorage.storage.application.dtos.FileDeleteResult;
import com.omnistorage.storage.application.dtos.FileUploadResult;
import com.omnistorage.storage.core.enums.StorageProvider;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.util.List;

public interface FileUseCase {
    List<FileUploadResult> uploadBulk(StorageProvider provider, String bucket, MultipartFile[] files);

    void downloadBulkAsZip(StorageProvider provider, String bucket, List<String> fileNames, OutputStream outputStream);

    List<FileDeleteResult> deleteBulk(StorageProvider provider, String bucket, List<String> fileNames);
}
