package com.vibemusic.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * 图片代理：解决网易云封面 HTTP 在移动端 HTTPS 页面被拦截的问题
 * 前端请求 /api/image-proxy?url=... → 后端拉取 HTTP 图片 → 通过 cpolar HTTPS 隧道返回
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ProxyController {

    private static final List<String> ALLOWED_HOSTS = Arrays.asList(
            "music.126.net", "p1.music.126.net", "p2.music.126.net",
            "p3.music.126.net", "p4.music.126.net"
    );

    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_SIZE = 5 * 1024 * 1024; // 5MB 上限

    @GetMapping("/image-proxy")
    public void proxyImage(@RequestParam String url, HttpServletResponse response) {
        if (url == null || url.isEmpty()) {
            response.setStatus(400);
            return;
        }

        try {
            URI uri = URI.create(url);
            String host = uri.getHost();

            // 安全检查：只代理网易云 CDN
            if (host == null || ALLOWED_HOSTS.stream().noneMatch(h -> host.equals(h) || host.endsWith("." + h))) {
                log.warn("Proxy blocked: {}", host);
                response.setStatus(403);
                return;
            }

            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setInstanceFollowRedirects(true);

                String contentType = conn.getContentType();
                if (contentType != null) {
                    response.setContentType(contentType);
                }

                int contentLength = conn.getContentLength();
                if (contentLength > MAX_SIZE) {
                    log.warn("Proxy image too large: {} bytes", contentLength);
                    response.setStatus(413);
                    conn.disconnect();
                    return;
                }

                // 缓存 1 小时（网易云封面基本不变）
                response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=3600");
                response.setHeader(HttpHeaders.ETAG, "\"" + url.hashCode() + "\"");

                try (InputStream in = conn.getInputStream();
                     OutputStream out = response.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }

        } catch (Exception e) {
            log.warn("Image proxy failed for {}: {}", url, e.getMessage());
            response.setStatus(502);
        }
    }
}
