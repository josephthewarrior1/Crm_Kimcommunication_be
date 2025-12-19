package com.pms.config;

import com.pms.service.DocumentStorageService;
import com.pms.service.LocalDocumentStorageService;
import com.pms.service.S3DocumentStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Bean
    public DocumentStorageService documentStorageService(
            @Value("${storage.type:local}") String type,
            @Value("${storage.local-dir:uploads}") String localDir,
            @Value("${storage.s3.endpoint:http://localhost:9000}") String s3Endpoint,
            @Value("${storage.s3.accessKey:minioadmin}") String s3Access,
            @Value("${storage.s3.secretKey:minioadmin}") String s3Secret,
            @Value("${storage.s3.bucket:pms-docs}") String s3Bucket
    ) throws Exception {
        if ("s3".equalsIgnoreCase(type)) {
            try {
                return new S3DocumentStorageService(s3Endpoint, s3Access, s3Secret, s3Bucket);
            } catch (Exception e) {
                // Fallback to local when S3/MinIO is unreachable or misconfigured
                return new LocalDocumentStorageService(localDir);
            }
        }
        return new LocalDocumentStorageService(localDir);
    }
}

