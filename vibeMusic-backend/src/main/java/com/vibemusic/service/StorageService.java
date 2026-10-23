package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.config.StorageConfig;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MinIO 对象存储服务（可开关）
 *
 * <p>运行态约定：云上只跑 backend/musicapi/mysql/redis，MinIO 是可选组件。</p>
 * <ul>
 *   <li>{@code storage.enabled=false}（缺省）：整个对象存储视为不存在，<b>不发起任何网络调用</b>，
 *       调用方走上游直连链路（{@link AudioProxyService}）。</li>
 *   <li>{@code storage.enabled=true}：本地演示/完整栈使用；MinIO 连不上时靠 60 秒短期熔断
 *       避免每个请求都吃一次连接超时。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    /** 熔断窗口：一次失败后 60 秒内直接判定不可用，不再发起网络请求。 */
    static final long CIRCUIT_OPEN_MS = 60_000L;

    private final StorageConfig config;
    private MinioClient client;

    /** 对象存储总开关（缺省关闭，云上是常态）。 */
    @Value("${storage.enabled:false}")
    private boolean enabled;

    /**
     * 桶可用性只探测一次的闸门：首个碰 MinIO 的调用者执行 bucketExists/makeBucket，
     * 其余调用直接放行。生产已不部署 MinIO，若每次调用都连 127.0.0.1:9000 会反复吃连接超时。
     */
    private final AtomicBoolean bucketCheckDone = new AtomicBoolean(false);

    /** 熔断到期时间戳（毫秒）；当前时间小于它即判定不可用，不发起任何网络调用。 */
    private final AtomicLong unavailableUntil = new AtomicLong(0L);

    @PostConstruct
    public void init() {
        // 仅构建客户端（纯本地对象，不发起网络请求）。桶的存在性检查推迟到首次真正访问 MinIO 时，
        // 避免启动路径上卡在 bucketExists 的连接超时（生产无 MinIO，旧实现每次启动都 ERROR）。
        this.client = MinioClient.builder()
                .endpoint(config.getEndpoint())
                .credentials(config.getAccessKey(), config.getSecretKey())
                .build();
    }

    /** 开关是否打开（配置层面）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 当前是否「真的能用」：开关打开且不在熔断窗口内。
     *
     * <p>调用方（StreamController / DownloadController / DownloadService）据此决定走 MinIO 还是直连代理，
     * 不要在调用前自己 try/catch 连接超时。</p>
     */
    public boolean isAvailable() {
        if (!enabled) return false;
        if (System.currentTimeMillis() < unavailableUntil.get()) return false;
        ensureBucket();
        return System.currentTimeMillis() >= unavailableUntil.get();
    }

    /** 记录一次失败并打开熔断窗口（到期后允许再试一次）。 */
    private void markUnavailable(String reason) {
        unavailableUntil.set(System.currentTimeMillis() + CIRCUIT_OPEN_MS);
        // 允许熔断到期后重新探测桶（MinIO 可能已被拉起）
        bucketCheckDone.set(false);
        log.warn("对象存储不可用，{} 秒内熔断不再重试: {}", CIRCUIT_OPEN_MS / 1000, reason);
    }

    /**
     * 惰性确保存储桶存在：进程内只尝试一次（失败开熔断、不抛异常）。
     */
    private void ensureBucket() {
        if (bucketCheckDone.get()) return;
        synchronized (this) {
            if (bucketCheckDone.get()) return;
            try {
                boolean exists = client.bucketExists(
                        BucketExistsArgs.builder().bucket(config.getBucketName()).build());
                if (!exists) {
                    client.makeBucket(
                            MakeBucketArgs.builder().bucket(config.getBucketName()).build());
                    log.info("存储桶 {} 创建成功", config.getBucketName());
                }
                bucketCheckDone.set(true);
            } catch (Exception e) {
                markUnavailable("桶检查失败: " + e.getMessage());
            }
        }
    }

    /**
     * 检查文件是否存在（开关关闭/熔断中 → 直接 false，无网络调用）
     */
    public boolean exists(String objectName) {
        if (!isAvailable()) return false;
        try {
            client.statObject(
                    StatObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .build());
            return true;
        } catch (ErrorResponseException e) {
            // 对象不存在 / 4xx：属正常结果，不算存储故障，不能开熔断
            log.debug("MinIO statObject 未命中: object={}, err={}", objectName, e.getMessage());
            return false;
        } catch (Exception e) {
            // 网络/5xx：开熔断，避免后续请求继续吃连接超时
            log.debug("MinIO statObject 失败，按未缓存处理: object={}, err={}", objectName, e.getMessage());
            markUnavailable("statObject 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取直接访问URL（不过期，依赖 MinIO 公开访问策略）
     */
    public String getDirectUrl(String objectName) {
        return config.getEndpoint() + "/" + config.getBucketName() + "/" + objectName;
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
        requireUsable();
        ensureBucket();
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
            throw new BusinessException(500, "文件上传失败");
        }
    }

    /**
     * 获取文件大小（字节）；开关关闭/熔断中 → 503
     */
    public long getObjectSize(String objectName) {
        requireUsable();
        ensureBucket();
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
     * 从 MinIO 读取完整文件流；开关关闭/熔断中 → 503
     */
    public java.io.InputStream getObject(String objectName) {
        requireUsable();
        ensureBucket();
        try {
            return client.getObject(
                    GetObjectArgs.builder()
                            .bucket(config.getBucketName())
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            log.error("读取文件失败: {}", e.getMessage());
            throw new BusinessException(404, "文件不存在或读取失败");
        }
    }

    /**
     * 从 MinIO 读取文件的部分内容（支持 Range：seek 加速）；开关关闭/熔断中 → 503
     * @param objectName 对象名
     * @param offset     起始偏移（字节）
     * @param length     读取长度（-1 表示到末尾）
     */
    public java.io.InputStream getObjectRange(String objectName, long offset, long length) {
        requireUsable();
        ensureBucket();
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
            throw new BusinessException(500, "文件范围读取失败");
        }
    }

    /** 开关关闭/熔断中：任何真实读写都直接 503，绝不发起网络调用。 */
    private void requireUsable() {
        if (!enabled) {
            throw new BusinessException(503, "对象存储未启用");
        }
        if (System.currentTimeMillis() < unavailableUntil.get()) {
            throw new BusinessException(503, "对象存储暂不可用（熔断中）");
        }
    }
}
