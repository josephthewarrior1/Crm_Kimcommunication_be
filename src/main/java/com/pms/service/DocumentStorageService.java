package com.pms.service;

import org.springframework.web.multipart.MultipartFile;

public interface DocumentStorageService {
    class StoredFile {
        public final String filename;
        public final String url; // public URL to access
        public StoredFile(String filename, String url) { this.filename = filename; this.url = url; }
    }
    StoredFile store(MultipartFile file) throws Exception;
}

