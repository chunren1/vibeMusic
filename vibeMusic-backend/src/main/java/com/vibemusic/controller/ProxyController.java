package com.vibemusic.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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
            "p3.music.126.net", "p4.music.126.net",
            // QQ 音乐封面 CDN：浏览器直连在部分网络下会 408/CORS，统一走后端代理
            "y.gtimg.cn", "i.gtimg.cn", "music.gtimg.cn"
    );

    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_SIZE = 5 * 1024 * 1024; // 5MB 上限
    private static final int MAX_REDIRECTS = 3; // 重定向最大跳数
    private static final int COPY_BUFFER_SIZE = 8192;

    @Operation(summary = "图片代理：拉取网易云/QQ 封面 CDN 图片并回传（仅白名单域名，防 SSRF）")
    @GetMapping("/image-proxy")
    public void proxyImage(@RequestParam String url, HttpServletResponse response) {
        if (url == null || url.isEmpty()) {
            response.setStatus(400);
            return;
        }

        try {
            // 安全检查：scheme 必须 http/https 且 host 在白名单内（含重定向目标，防 SSRF）
            if (!isAllowedUrl(url)) {
                log.warn("Proxy blocked: {}", url);
                response.setStatus(403);
                return;
            }

            URI uri = URI.create(url);

            HttpURLConnection conn = null;
            try {
                conn = openWithRedirects(uri, 0);

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
                    // 流式拷贝并计数：chunked 响应 Content-Length=-1 时也能拦截超限
                    copyWithLimit(in, out, MAX_SIZE);
                }
            } finally {
                if (conn != null) conn.disconnect();
            }

        } catch (SizeLimitExceededException e) {
            log.warn("Proxy image exceeded {} bytes: {}", MAX_SIZE, url);
            response.setStatus(413);
        } catch (Exception e) {
            log.warn("Image proxy failed for {}: {}", url, e.getMessage());
            response.setStatus(502);
        }
    }

    /**
     * 校验完整 URL：scheme 必须为 http/https，且 host 在白名单内（或其子域）。
     * 包私有静态方法，便于单元测试。
     */
    static boolean isAllowedUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            return isAllowedHost(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验 host：精确匹配白名单域名，或为其子域（带点前缀，防止 music.126.net.evil.com 绕过）。
     * 包私有静态方法，便于单元测试。
     */
    static boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }
        return ALLOWED_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
    }

    /**
     * 手动跟随重定向：每跳重新校验目标 URL（scheme + host），最多 MAX_REDIRECTS 跳。
     * 防止攻击者利用白名单域名上的 302 跳转到内网地址（SSRF 绕过）。
     */
    private HttpURLConnection openWithRedirects(URI uri, int hops) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setInstanceFollowRedirects(false);

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isEmpty()) {
                throw new IOException("Redirect without Location header");
            }
            if (hops >= MAX_REDIRECTS) {
                throw new IOException("Too many redirects, max " + MAX_REDIRECTS);
            }
            URI target = uri.resolve(location);
            if (!isAllowedUrl(target.toString())) {
                throw new IOException("Redirect target not allowed: " + target);
            }
            log.info("Proxy redirect {} -> {}", uri, target);
            return openWithRedirects(target, hops + 1);
        }
        return conn;
    }

    /**
     * 流拷贝并计数：超过 maxBytes 立即抛异常中止。
     * 防止 chunked 响应（Content-Length=-1）绕过 5MB 上限检查。
     */
    private static void copyWithLimit(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buf = new byte[COPY_BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new SizeLimitExceededException("Stream exceeded " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        out.flush();
    }

    /** 流式拷贝超限异常（内部使用，触发 413） */
    private static class SizeLimitExceededException extends IOException {
        SizeLimitExceededException(String message) {
            super(message);
        }
    }
}