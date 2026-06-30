package com.idata.profile.infra.minio;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MinIO对象存储封装。用于课题三media_asset的storageUri本地镜像、
 * 批量导入文件存储、报告导出文件存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    public String upload(String bucket, String key, byte[] content, String contentType) {
        // TODO: minioClient.putObject(...)
        throw new UnsupportedOperationException("待补充MinIO客户端具体调用");
    }

    public byte[] download(String bucket, String key) {
        // TODO: minioClient.getObject(...)
        throw new UnsupportedOperationException("待补充MinIO客户端具体调用");
    }
}
