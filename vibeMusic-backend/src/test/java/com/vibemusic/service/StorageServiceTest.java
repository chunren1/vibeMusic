package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.config.StorageConfig;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    // ---------- 开关打开后的真实读写路径（MinioClient 全 mock，不联网） ----------

    /** 构造一个「开关打开」的实例；MinIO 客户端仍是 mock，任何真实网络调用都测不出来。 */
    private StorageService enabledService(MinioClient client) {
        StorageService service = new StorageService(new StorageConfig());
        ReflectionTestUtils.setField(service, "client", client);
        ReflectionTestUtils.setField(service, "enabled", true);
        return service;
    }

    private static AtomicLong circuitDeadlineOf(StorageService service) {
        return (AtomicLong) ReflectionTestUtils.getField(service, "unavailableUntil");
    }

    @Test
    @DisplayName("开关打开且桶存在：isAvailable() 为 true，桶探测全程只做一次")
    void availableWhenBucketExistsAndProbesOnlyOnce() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        StorageService service = enabledService(client);

        assertThat(service.isEnabled()).isTrue();
        assertThat(service.isAvailable()).isTrue();
        assertThat(service.isAvailable()).isTrue();

        verify(client, times(1)).bucketExists(any());
        verify(client, never()).makeBucket(any());
    }

    @Test
    @DisplayName("桶不存在时自动创建，且只创建一次")
    void createsBucketOnlyOnceWhenMissing() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(false);
        StorageService service = enabledService(client);

        assertThat(service.isAvailable()).isTrue();
        assertThat(service.isAvailable()).isTrue();

        verify(client, times(1)).makeBucket(any());
    }

    @Test
    @DisplayName("uploadStream 正常：桶名/对象名原样透传，contentType 缺省 audio/mpeg")
    void uploadStreamDefaultsContentTypeToAudioMpeg() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        StorageService service = enabledService(client);

        String url = service.uploadStream("songs/abc.mp3", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, null);

        assertThat(url).isEqualTo("http://127.0.0.1:9000/vibemusic/songs/abc.mp3");
        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo("vibemusic");
        assertThat(args.object()).isEqualTo("songs/abc.mp3");
        assertThat(args.objectSize()).isEqualTo(3L);
        assertThat(args.contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    @DisplayName("uploadStream 显式 contentType 优先，不被 audio/mpeg 覆盖")
    void uploadStreamKeepsExplicitContentType() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        StorageService service = enabledService(client);

        service.uploadStream("songs/abc.flac", new ByteArrayInputStream(new byte[]{1}), 1, "audio/flac");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(captor.capture());
        assertThat(captor.getValue().contentType()).isEqualTo("audio/flac");
    }

    @Test
    @DisplayName("uploadStream 遇 MinIO 异常：转 500 BusinessException，不泄露底层错误原文")
    void uploadStreamWrapsMinioFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.putObject(any())).thenThrow(new RuntimeException("connection reset by peer"));
        StorageService service = enabledService(client);

        assertThatThrownBy(() -> service.uploadStream(
                "songs/abc.mp3", new ByteArrayInputStream(new byte[]{1}), 1, "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件上传失败")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(500));
    }

    @Test
    @DisplayName("熔断期内 uploadStream 直接 503，不发起 putObject")
    void uploadStreamThrows503WhileCircuitOpen() {
        StorageService service = disabledService();
        ReflectionTestUtils.setField(service, "enabled", true);
        circuitDeadlineOf(service).set(System.currentTimeMillis() + 10_000);

        assertThatThrownBy(() -> service.uploadStream(
                "songs/abc.mp3", new ByteArrayInputStream(new byte[]{1}), 1, "audio/mpeg"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("熔断中")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(503));

        verifyNoInteractions(clientOf(service));
    }

    @Test
    @DisplayName("exists 命中返回 true（对象在 MinIO 上）")
    void existsReturnsTrueWhenStatSucceeds() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.statObject(any())).thenReturn(mock(StatObjectResponse.class));
        StorageService service = enabledService(client);

        assertThat(service.exists("songs/abc.mp3")).isTrue();
    }

    @Test
    @DisplayName("exists 遇 ErrorResponseException（对象不存在）返回 false，且不误开熔断")
    void existsTreatsMissingObjectAsNormalResult() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.statObject(any())).thenThrow(mock(ErrorResponseException.class));
        StorageService service = enabledService(client);

        assertThat(service.exists("songs/missing.mp3")).isFalse();

        assertThat(circuitDeadlineOf(service).get()).as("4xx 不是存储故障，不能熔断").isZero();
        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("exists 遇网络/5xx 异常返回 false 并开熔断，后续调用零网络")
    void existsOpensCircuitOnUnexpectedFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.statObject(any())).thenThrow(new RuntimeException("connect timed out"));
        StorageService service = enabledService(client);

        assertThat(service.exists("songs/abc.mp3")).isFalse();
        assertThat(circuitDeadlineOf(service).get()).isGreaterThan(System.currentTimeMillis());

        assertThat(service.exists("songs/abc.mp3")).isFalse();
        verify(client, times(1)).statObject(any());
    }

    @Test
    @DisplayName("getObjectSize 正常返回字节数")
    void getObjectSizeReturnsSize() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1234L);
        when(client.statObject(any())).thenReturn(stat);
        StorageService service = enabledService(client);

        assertThat(service.getObjectSize("songs/abc.mp3")).isEqualTo(1234L);
    }

    @Test
    @DisplayName("getObjectSize 失败降级返回 -1，不抛异常（调用方按未知大小处理）")
    void getObjectSizeDegradesToMinusOne() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.statObject(any())).thenThrow(new RuntimeException("boom"));
        StorageService service = enabledService(client);

        assertThat(service.getObjectSize("songs/abc.mp3")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("getObject 正常返回 MinIO 流本体")
    void getObjectReturnsMinioStream() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(client.getObject(any())).thenReturn(response);
        StorageService service = enabledService(client);

        assertThat(service.getObject("songs/abc.mp3")).isSameAs(response);
    }

    @Test
    @DisplayName("getObject 失败转 404 BusinessException（对象不存在或读取失败）")
    void getObjectThrows404OnFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.getObject(any())).thenThrow(new RuntimeException("no such key"));
        StorageService service = enabledService(client);

        assertThatThrownBy(() -> service.getObject("songs/abc.mp3"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件不存在或读取失败")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
    }

    @Test
    @DisplayName("getObjectRange 透传 offset/length（Range 播放 seek 依赖此参数）")
    void getObjectRangePassesOffsetAndLength() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(client.getObject(any())).thenReturn(response);
        StorageService service = enabledService(client);

        assertThat(service.getObjectRange("songs/abc.mp3", 100, 200)).isSameAs(response);

        ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(captor.capture());
        GetObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo("vibemusic");
        assertThat(args.object()).isEqualTo("songs/abc.mp3");
        assertThat(args.offset()).isEqualTo(100L);
        assertThat(args.length()).isEqualTo(200L);
    }

    @Test
    @DisplayName("getObjectRange 失败转 500（Range 失败不能伪装成 404，否则播放器直接放弃）")
    void getObjectRangeThrows500OnFailure() throws Exception {
        MinioClient client = mock(MinioClient.class);
        when(client.bucketExists(any())).thenReturn(true);
        when(client.getObject(any())).thenThrow(new RuntimeException("boom"));
        StorageService service = enabledService(client);

        assertThatThrownBy(() -> service.getObjectRange("songs/abc.mp3", 0, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文件范围读取失败")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(500));
    }

    @Test
    @DisplayName("熔断期内读取类方法同样 503，不发起 getObject")
    void readOperationsThrow503WhileCircuitOpen() {
        StorageService service = disabledService();
        ReflectionTestUtils.setField(service, "enabled", true);
        circuitDeadlineOf(service).set(System.currentTimeMillis() + 10_000);

        assertThatThrownBy(() -> service.getObject("songs/abc.mp3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("熔断中");
        assertThatThrownBy(() -> service.getObjectSize("songs/abc.mp3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("熔断中");

        verifyNoInteractions(clientOf(service));
    }

    @Test
    @DisplayName("getDirectUrl 直接拼 endpoint/bucket/object，不做 URL 编码")
    void getDirectUrlJoinsEndpointBucketAndObject() {
        StorageService service = new StorageService(new StorageConfig());

        assertThat(service.getDirectUrl("songs/abc.mp3"))
                .isEqualTo("http://127.0.0.1:9000/vibemusic/songs/abc.mp3");
    }

    @Test
    @DisplayName("熔断窗口固定 60 秒（与注释/运维预期一致）")
    void circuitWindowIs60Seconds() {
        assertThat(StorageService.CIRCUIT_OPEN_MS).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("init() 只构建 MinioClient，不发起任何网络请求（启动路径不能卡连接超时）")
    void initOnlyBuildsClientWithoutNetworkCall() {
        StorageConfig config = new StorageConfig();
        config.setEndpoint("http://127.0.0.1:9000");
        config.setAccessKey("test-key");
        config.setSecretKey("test-secret");
        StorageService service = new StorageService(config);

        service.init();

        assertThat(ReflectionTestUtils.getField(service, "client")).isInstanceOf(MinioClient.class);
        // 未探测桶 → 后续 isAvailable() 在开关关闭时也不会联网
        assertThat(service.isAvailable()).isFalse();
    }
}
