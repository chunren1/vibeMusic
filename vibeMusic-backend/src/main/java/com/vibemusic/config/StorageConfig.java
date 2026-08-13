package com.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置 (MinIO)
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "storage.minio")
public class StorageConfig {

    /** 服务地址 */
    private String endpoint = "http://127.0.0.1:9000";

    /** Access Key（仅从环境变量/yml 注入，禁止硬编码） */
    private String accessKey = "";

    /** Secret Key（仅从环境变量/yml 注入，禁止硬编码） */
    private String secretKey = "";

    /** 存储桶名称 */
    private String bucketName = "vibemusic";

    /** 数据存储根路径 */
    private String dataPath = "D:\\Mindata";
}
