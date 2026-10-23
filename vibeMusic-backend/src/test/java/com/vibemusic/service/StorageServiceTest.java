package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.config.StorageConfig;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * StorageService 开关语义单元测试（不启动 Spring，不连真实 MinIO）。
 *
 * <p>核心契约：{@code storage.enabled=false} 时整个对象存储视为不存在，
 * 不得发起任何网络调用；开关打开但连不上时靠 60 秒熔断快速失败。</p>
 */
@DisplayName("StorageService 对象存储开关与熔断")
class StorageServiceTest {

    /** 构造一个已注入「已禁用」状态的实例（@Value 在纯单测中不生效，boolean 缺省即 false）。 */
    private StorageService disabledService() {
        StorageService service = new StorageService(new StorageConfig());
        MinioClient client = mock(MinioClient.class);
        ReflectionTestUtils.setField(service, "client", client);
        return service;
    }

    private MinioClient clientOf(StorageService service) {
        return (MinioClient) ReflectionTestUtils.getField(service, "client");
    }

    @Test
    @DisplayName("开关关闭时 exists() 返回 false 且不触发任何 MinIO 网络调用")
    void existsReturnsFalseWithoutNetworkWhenDisabled() {
        StorageService service = disabledService();

        assertThat(service.isEnabled()).isFalse();
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.exists("songs/x.mp3")).isFalse();

        verifyNoInteractions(clientOf(service));
    }

    @Test
    @DisplayName("开关关闭时 uploadStream() 抛 503 BusinessException（对象存储未启用）")
    void uploadStreamThrows503WhenDisabled() {
        StorageService service = disabledService();

        assertThatThrownBy(() -> service.uploadStream(
                "songs/x.mp3", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对象存储未启用")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(503));

        verifyNoInteractions(clientOf(service));
    }

    @Test
    @DisplayName("开关关闭时读取类方法一律 503，不返回半截数据")
    void readOperationsThrow503WhenDisabled() {
        StorageService service = disabledService();

        assertThatThrownBy(() -> service.getObject("songs/x.mp3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对象存储未启用");
        assertThatThrownBy(() -> service.getObjectRange("songs/x.mp3", 0, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对象存储未启用");
        assertThatThrownBy(() -> service.getObjectSize("songs/x.mp3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对象存储未启用");

        verifyNoInteractions(clientOf(service));
    }

    @Test
    @DisplayName("开关打开但 MinIO 连不上时：首次探测失败即熔断，60 秒内不再重试")
    void circuitOpensFor60sAfterFirstFailure() throws Exception {
        StorageService service = new StorageService(new StorageConfig());
        MinioClient client = mock(MinioClient.class);
        // 模拟「开关打开但 MinIO 不存在」：探测即连接失败
        when(client.bucketExists(any())).thenThrow(new RuntimeException("Failed to connect to /127.0.0.1:9000"));
        ReflectionTestUtils.setField(service, "client", client);
        ReflectionTestUtils.setField(service, "enabled", true);

        assertThat(service.isAvailable()).isFalse();   // 首次探测：失败并开熔断

        AtomicLong unavailableUntil = (AtomicLong) ReflectionTestUtils.getField(service, "unavailableUntil");
        assertThat(unavailableUntil.get())
                .as("熔断到期时间应被推后到未来（约 60 秒）")
                .isGreaterThan(System.currentTimeMillis());

        long startedAt = System.nanoTime();
        assertThat(service.isAvailable()).isFalse();
        assertThat(service.exists("songs/x.mp3")).isFalse();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).as("熔断期内应零网络调用、立即返回").isLessThan(200);

        // 熔断期内绝不重复探测 MinIO
        verify(client, times(1)).bucketExists(any());
        verify(client, never()).statObject(any());
    }
}
