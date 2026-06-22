package com.omni.platform.modules.storages.usecases;

import java.io.OutputStream;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.omni.platform.modules.storages.dtos.FileDeleteResult;
import com.omni.platform.modules.storages.dtos.FileUploadResult;
import com.omni.platform.shared.enums.StorageProvider;

public interface FileUseCase {
    List<FileUploadResult> uploadBulk(StorageProvider provider, String bucket, MultipartFile[] files);

    void downloadBulkAsZip(StorageProvider provider, String bucket, List<String> fileNames, OutputStream outputStream);

    List<FileDeleteResult> deleteBulk(StorageProvider provider, String bucket, List<String> fileNames);
}
