package com.omni.platform.modules.storages.controllers;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.omni.platform.modules.storages.dtos.FileUploadResult;
import com.omni.platform.modules.storages.usecases.FileUseCaseService;
import com.omni.platform.shared.enums.StorageProvider;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {
    private final FileUseCaseService fileUseCaseService;

    @PostMapping("/{provider}/upload-bulk/{bucket}")
    public ResponseEntity<List<FileUploadResult>> uploadBulk(
            @PathVariable StorageProvider provider,
            @PathVariable String bucket,
            @RequestParam("files") MultipartFile[] files) {

        // Use Virtual Threads to handle parallel upload
        List<FileUploadResult> results = fileUseCaseService.uploadBulk(provider, bucket, files);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{provider}/download-bulk/{bucket}")
    public void downloadBulk(
            @PathVariable StorageProvider provider,
            @PathVariable String bucket,
            @RequestParam List<String> fileNames,
            HttpServletResponse response) throws IOException {

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=files.zip");

        // Read each file from Provider then write into Zip
        fileUseCaseService.downloadBulkAsZip(provider, bucket, fileNames, response.getOutputStream());
    }
}
