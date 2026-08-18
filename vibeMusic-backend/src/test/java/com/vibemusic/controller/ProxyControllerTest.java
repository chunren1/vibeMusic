package com.vibemusic.controller;

import com.vibemusic.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ProxyController 测试")
class ProxyControllerTest extends BaseTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Nested
    @DisplayName("静态校验方法 isAllowedHost / isAllowedUrl")
    class HostValidation {

        @Test
        @DisplayName("白名单主域名 → 通过")
        void shouldAllowExactWhitelistedHost() {
            assertTrue(ProxyController.isAllowedHost("music.126.net"));
            assertTrue(ProxyController.isAllowedHost("y.gtimg.cn"));
            assertTrue(ProxyController.isAllowedHost("i.gtimg.cn"));
            assertTrue(ProxyController.isAllowedHost("music.gtimg.cn"));
        }

        @Test
        @DisplayName("白名单子域（p1.music.126.net）→ 通过")
        void shouldAllowSubdomainOfWhitelistedHost() {
            assertTrue(ProxyController.isAllowedHost("p1.music.126.net"));
            assertTrue(ProxyController.isAllowedHost("p2.music.126.net"));
            assertTrue(ProxyController.isAllowedHost("p3.music.126.net"));
            assertTrue(ProxyController.isAllowedHost("p4.music.126.net"));
        }

        @Test
        @DisplayName("非白名单域名 → 拒绝")
        void shouldRejectUnknownHost() {
            assertFalse(ProxyController.isAllowedHost("evil.com"));
            assertFalse(ProxyController.isAllowedHost("example.com"));
        }

        @Test
        @DisplayName("白名单域名的伪造后缀（music.126.net.evil.com）→ 拒绝")
        void shouldRejectSuffixSpoofing() {
            assertFalse(ProxyController.isAllowedHost("music.126.net.evil.com"));
            assertFalse(ProxyController.isAllowedHost("evilmusic.126.net"));
            assertFalse(ProxyController.isAllowedHost("p1.music.126.net.evil.com"));
        }

        @Test
        @DisplayName("内网地址（169.254.169.254 / 127.0.0.1 / localhost）→ 拒绝")
        void shouldRejectInternalAddresses() {
            assertFalse(ProxyController.isAllowedHost("169.254.169.254"));
            assertFalse(ProxyController.isAllowedHost("127.0.0.1"));
            assertFalse(ProxyController.isAllowedHost("localhost"));
            assertFalse(ProxyController.isAllowedHost("10.0.0.1"));
        }

        @Test
        @DisplayName("null host → 拒绝")
        void shouldRejectNullHost() {
            assertFalse(ProxyController.isAllowedHost(null));
        }

        @Test
        @DisplayName("http/https 白名单 URL → 通过")
        void shouldAllowHttpUrlsOnWhitelist() {
            assertTrue(ProxyController.isAllowedUrl("http://music.126.net/cover/1.jpg"));
            assertTrue(ProxyController.isAllowedUrl("https://p1.music.126.net/cover/1.jpg"));
            assertTrue(ProxyController.isAllowedUrl("https://y.gtimg.cn/music/cover/1.jpg"));
        }

        @Test
        @DisplayName("非 http(s) scheme（file:// / javascript: / jar:）→ 拒绝")
        void shouldRejectNonHttpSchemes() {
            assertFalse(ProxyController.isAllowedUrl("file:///etc/passwd"));
            assertFalse(ProxyController.isAllowedUrl("javascript:alert(1)"));
            assertFalse(ProxyController.isAllowedUrl("jar:file:///tmp/a.jar!/x"));
        }

        @Test
        @DisplayName("重定向目标校验：跳转到内网/非白名单 → 拒绝，白名单内 → 通过")
        void shouldValidateRedirectTargets() {
            // 模拟 302 Location 解析后的目标 URL 校验（openWithRedirects 每跳都会调用 isAllowedUrl）
            assertFalse(ProxyController.isAllowedUrl("http://169.254.169.254/latest/meta-data"));
            assertFalse(ProxyController.isAllowedUrl("http://127.0.0.1:8080/admin"));
            assertFalse(ProxyController.isAllowedUrl("http://music.126.net.evil.com/x"));
            assertFalse(ProxyController.isAllowedUrl("http://evil.com/x"));
            assertFalse(ProxyController.isAllowedUrl("file:///etc/shadow"));
            // 白名单内重定向 → 通过
            assertTrue(ProxyController.isAllowedUrl("https://p2.music.126.net/redirected.jpg"));
        }
    }

    @Nested
    @DisplayName("GET /api/image-proxy")
    class ImageProxy {

        @Test
        @DisplayName("缺少 url 参数 → 400")
        void shouldReturn400WhenUrlMissing() throws Exception {
            mockMvc.perform(get("/api/image-proxy"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("url 为空字符串 → 400")
        void shouldReturn400WhenUrlEmpty() throws Exception {
            mockMvc.perform(get("/api/image-proxy").param("url", ""))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("非白名单域名 → 403（短路，不发起网络请求）")
        void shouldReturn403ForBadHost() throws Exception {
            mockMvc.perform(get("/api/image-proxy").param("url", "http://evil.com/x.jpg"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("内网地址 → 403（短路，不发起网络请求）")
        void shouldReturn403ForInternalAddress() throws Exception {
            mockMvc.perform(get("/api/image-proxy").param("url", "http://169.254.169.254/latest/meta-data"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非 http(s) scheme → 403（短路，不发起网络请求）")
        void shouldReturn403ForNonHttpScheme() throws Exception {
            mockMvc.perform(get("/api/image-proxy").param("url", "file:///etc/passwd"))
                    .andExpect(status().isForbidden());
        }
    }
}