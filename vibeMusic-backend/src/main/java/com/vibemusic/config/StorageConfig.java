package com.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储配置 (RustFS)
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "storage.rustfs")
public class StorageConfig {

    /** 服务地址 */
    private String endpoint = "http://127.0.0.1:9000";

    /** Access Key */
    private String accessKey = "rustfsadmin";

    /** Secret Key */
    private String secretKey = "rustfsadmin";

    /** 存储桶名称 */
    private String bucketName = "vibemusic";

    /** 数据存储根路径 */
    private String dataPath = "D:\\Rustfsdata";
}
