package com.omni.platform.application.usecases;

import com.omni.platform.application.dtos.FileDeleteResult;
import com.omni.platform.application.dtos.FileUploadResult;
import com.omni.platform.core.enums.StorageProvider;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.util.List;

public interface FileUseCase {
    List<FileUploadResult> uploadBulk(StorageProvider provider, String bucket, MultipartFile[] files);

    void downloadBulkAsZip(StorageProvider provider, String bucket, List<String> fileNames, OutputStream outputStream);

    List<FileDeleteResult> deleteBulk(StorageProvider provider, String bucket, List<String> fileNames);
}
