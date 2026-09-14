package com.vibemusic.service;

import com.vibemusic.config.NeteaseApiConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.ExpectedCount.once;

/**
 * NeteaseApiService.downloadSongToFile URI 编码回归测试。
 * <p>
 * 与 StreamController 同一病因：曾用 {@code .uri(String)} 传递第三方 CDN 签名 URL，
 * 含 %-转义的咪咕 URL 会被二次编码（{@code %E5 → %25E5}）导致下载 404。
 */
@DisplayName("NeteaseApiService 下载 URI 编码回归测试")
class NeteaseApiServiceDownloadTest {

    private static final String ENCODED_URL =
            "https://audio.migu.cn/song/%E5%B9%B4/track.mp3?sign=abc123";

    private MockRestServiceServer server;
    private NeteaseApiService neteaseApiService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        neteaseApiService = new NeteaseApiService(
                mock(NeteaseApiConfig.class), mock(RestTemplate.class), builder);
        ReflectionTestUtils.setField(neteaseApiService, "cdnWhitelistConfig",
                "*.music.126.net,*.migu.cn");
        neteaseApiService.initStreamClient();
    }

    @Test
    @DisplayName("含 %-转义的 CDN URL → 下载请求 URI 原样透传，文件字节一致")
    void shouldNotDoubleEncodePreEncodedDownloadUrl() throws Exception {
        server.expect(once(), requestTo(ENCODED_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("FAKEAUDIO".getBytes(),
                        MediaType.parseMediaType("audio/mpeg")));

        java.io.File tempFile = neteaseApiService.downloadSongToFile(ENCODED_URL);
        try {
            server.verify();
            assertArrayEquals("FAKEAUDIO".getBytes(), Files.readAllBytes(tempFile.toPath()));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    @DisplayName("下载失败时删除临时文件且异常保留 cause")
    void failedDownloadCleansTempFileAndKeepsCause() throws Exception {
        java.nio.file.Path tmpDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long before;
        try (var s = Files.list(tmpDir)) {
            before = s.filter(p -> p.getFileName().toString().startsWith("vibemusic-dl-")).count();
        }
        server.expect(once(), requestTo(ENCODED_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        com.vibemusic.common.exception.BusinessException ex = assertThrows(
                com.vibemusic.common.exception.BusinessException.class,
                () -> neteaseApiService.downloadSongToFile(ENCODED_URL));

        assertEquals(502, ex.getCode());
        assertNotNull(ex.getCause(), "下载失败应保留原始 cause");
        try (var s = Files.list(tmpDir)) {
            long after = s.filter(p -> p.getFileName().toString().startsWith("vibemusic-dl-")).count();
            assertEquals(before, after, "失败的下载不应残留临时文件");
        }
    }
}
