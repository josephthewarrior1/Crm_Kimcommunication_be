package com.pms.service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public class S3DocumentStorageService implements DocumentStorageService {

    private final MinioClient client;
    private final String bucket;
    private final String publicBase;

    public S3DocumentStorageService(String endpoint, String accessKey, String secretKey, String bucket) throws Exception {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        this.publicBase = endpoint.replaceFirst("^http[s]?://", "") ;
        ensureBucket();
    }

    private void ensureBucket() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    @Override
    public StoredFile store(MultipartFile file) throws Exception {
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot > 0 && dot < original.length() - 1) ext = original.substring(dot);
        String key = UUID.randomUUID().toString().replace("-", "") + ext;
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                .stream(file.getInputStream(), file.getSize(), -1)
                .build());
        String url = String.format("/files/%s", key); // will be proxied if needed; adjust if public S3 URL required
        return new StoredFile(key, url);
    }
}

