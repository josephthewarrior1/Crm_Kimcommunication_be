package com.pms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public class LocalDocumentStorageService implements DocumentStorageService {

    private final Path root;

    public LocalDocumentStorageService(@Value("${storage.local-dir:uploads}") String localDir) throws IOException {
        this.root = Paths.get(localDir).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public StoredFile store(MultipartFile file) throws IOException {
        String original = StringUtils
                .cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0 && dot < original.length() - 1) {
            ext = original.substring(dot);
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = root.resolve(filename);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String url = "/files/" + filename;
        return new StoredFile(filename, url);
    }
}
