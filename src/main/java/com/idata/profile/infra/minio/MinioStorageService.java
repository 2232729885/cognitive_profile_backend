package com.idata.profile.infra.minio;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    public String upload(String bucket, String key, byte[] content, String contentType) {
        try {
            boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!bucketExists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .build());
            }

            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(inputStream, (long) content.length, -1L)
                        .contentType(contentType == null || contentType.isBlank()
                                ? "application/octet-stream" : contentType)
                        .build());
            }
            return bucket + "/" + key;
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload object to MinIO: " + bucket + "/" + key, e);
        }
    }

    public byte[] download(String bucket, String key) {
        try (GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .build())) {
            return response.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to download object from MinIO: " + bucket + "/" + key, e);
        }
    }
}
