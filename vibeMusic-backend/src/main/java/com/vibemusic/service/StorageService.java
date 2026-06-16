package com.vibemusic.service;

import com.vibemusic.config.StorageConfig;
import io.minio.*;
import io.minio.errors.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * RustFS 对象存储服务（通过 MinIO S3 协议）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageConfig config;
    private MinioClient client;

    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(config.getEndpoint())
                .credentials(config.getAccessKey(), config.getSecretKey())
                .build();

        // 自动创建存储桶
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(config.getBucketName()).build());
            if (!exists) {
                client.makeBucket(
                        MakeBucketArgs.builder().bucket(config.getBucketName()).build());
                log.info("存储桶 {} 创建成功", config.getBucketName());
            }
        } catch (Exception e) {
            log.error("存储桶创建失败: {}", e.getMessage());
        }
    }

    /**
     * 上传文件到 RustFS
     *
     * @param objectName 对象名（路径+文件名，如 songs/186016.mp3）
     * @param data       文件字节数据
     * @param contentType MIME类型
     * @return 访问 URL
     */
    public String upload(String objectName, byte[] data, String contentType) {
        try {
            // RustFS 单次 PUT 限制 10MB，设置 partSize=5MB 强制分块上传
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .stream(new ByteArrayInputStream(data), data.length, 5 * 1024 * 1024)
                            .contentType(contentType != null ? contentType : "audio/mpeg")
                            .build());

            // 返回公开访问地址
            String url = config.getEndpoint() + "/" + config.getBucketName() + "/" + objectName;
            log.info("上传成功: {} -> {} ({} bytes)", objectName, url, data.length);
            return url;
        } catch (Exception e) {
            log.error("上传失败: {} - {}", objectName, e.getMessage());
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 检查文件是否存在
     */
    public boolean exists(String objectName) {
        try {
            client.statObject(
                    StatObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取直接访问URL（不过期，依赖RustFS公开访问策略）
     */
    public String getDirectUrl(String objectName) {
        return config.getEndpoint() + "/" + config.getBucketName() + "/" + objectName;
    }

    /**
     * 获取临时访问URL（有效期7天）
     */
    public String getPresignedUrl(String objectName) {
        try {
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .expiry(7 * 24 * 60 * 60) // 7天
                            .build());
        } catch (Exception e) {
            log.error("生成预签名URL失败: {}", e.getMessage());
            return config.getEndpoint() + "/" + config.getBucketName() + "/" + objectName;
        }
    }

    /**
     * 流式上传（直接使用 InputStream，避免全量加载到内存）
     *
     * @param objectName 对象名
     * @param inputStream 输入流
     * @param size 流大小（字节，-1 表示未知）
     * @param contentType MIME类型
     * @return 访问 URL
     */
    public String uploadStream(String objectName, java.io.InputStream inputStream, long size, String contentType) {
        try {
            client.putObject(
                    PutObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .stream(inputStream, size, 5 * 1024 * 1024)
                            .contentType(contentType != null ? contentType : "audio/mpeg")
                            .build());
            String url = config.getEndpoint() + "/" + config.getBucketName() + "/" + objectName;
            log.info("流式上传成功: {} -> {} ({} bytes)", objectName, url, size);
            return url;
        } catch (Exception e) {
            log.error("流式上传失败: {} - {}", objectName, e.getMessage());
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 获取文件大小（字节）
     */
    public long getObjectSize(String objectName) {
        try {
            return client.statObject(
                    StatObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .build()).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 从 RustFS 读取完整文件流
     */
    public java.io.InputStream getObject(String objectName) {
        try {
            return client.getObject(
                    GetObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            log.error("读取文件失败: {}", e.getMessage());
            throw new RuntimeException("文件不存在或读取失败", e);
        }
    }

    /**
     * 从 RustFS 读取文件的部分内容（支持 Range：seek 加速）
     * @param objectName 对象名
     * @param offset     起始偏移（字节）
     * @param length     读取长度（-1 表示到末尾）
     */
    public java.io.InputStream getObjectRange(String objectName, long offset, long length) {
        try {
            return client.getObject(
                    GetObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .offset(offset)
                            .length(length)
                            .build());
        } catch (Exception e) {
            log.error("范围读取文件失败: {}", e.getMessage());
            throw new RuntimeException("文件范围读取失败", e);
        }
    }
}
