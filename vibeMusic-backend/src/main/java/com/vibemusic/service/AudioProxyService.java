package com.vibemusic.service;

import com.vibemusic.common.utils.CdnWhitelist;
import com.vibemusic.common.utils.StreamUtils;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 音频流上游直连代理（唯一实现）。
 *
 * <p>历史背景：这套「取播放地址 → 校验 CDN 白名单 → 带 Range 转发 → 失败重试」的逻辑原先只存在于
 * {@code StreamController.streamFromRemote}。下载接口在「对象存储不可用」时也需要同一条链路，
 * 为避免出现孪生副本（本项目历史最重 bug 根因），此处收敛为唯一实现：</p>
 * <ul>
 *   <li>{@link com.vibemusic.controller.StreamController} 委托调用（{@code asAttachment=false}）</li>
 *   <li>{@link com.vibemusic.controller.DownloadController} 委托调用（{@code asAttachment=true}）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioProxyService {

    private final SongPlayService songPlayService;
    private final RestClient.Builder restClientBuilder;

    private RestClient restClient;

    /**
     * 音频字节流专用客户端（30s 读空闲）：CDN 传输受手机端背压节流，
     * 不能套用 JSON 的 5s 响应超时，否则播放中途会被误杀。
     * Spring 环境下注入 {@code streamRestClient} bean；单测手工构造时为 null，回退 restClientBuilder。
     */
    @Autowired(required = false)
    void setStreamRestClient(@Qualifier("streamRestClient") RestClient streamRestClient) {
        this.restClient = streamRestClient;
    }

    @PostConstruct
    void initRestClient() {
        if (this.restClient == null) this.restClient = restClientBuilder.build();
    }

    /** 音频 CDN 域名通配符白名单（防 SSRF），支持 *.music.126.net 风格 */
    @Value("${stream.cdn-whitelist}")
    private String cdnWhitelistConfig;

    /** B 站 upos CDN 强制 Referer，否则 403。仅 bilivideo/hdslb 域名需要。 */
    public static final String BILIBILI_REFERER = "https://www.bilibili.com/";

    /** 单次音频流代理的总预算：每次 getPlayUrl 自带 8s 内部预算，串行 5 次最坏 40s
     *  会把 Tomcat 工作线程占死。这里给整个重试循环一个总闸，超预算即终止。 */
    public static final long STREAM_TOTAL_BUDGET_MS = 12_000L;

    /** 纯函数：是否还允许下一次尝试（预算未耗尽且未超过次数上限）。 */
    public static boolean canRetryStream(int attempt, int maxAttempts, long deadlineMs, long nowMs) {
        return attempt < maxAttempts && nowMs <= deadlineMs;
    }

    private List<String> loadCdnWhitelist() {
        return CdnWhitelist.parse(cdnWhitelistConfig);
    }

    private boolean isCdnWhitelisted(String host) {
        if (host == null) return false;
        boolean ok = CdnWhitelist.matches(host, loadCdnWhitelist());
        if (!ok) {
            log.warn("SSRF blocked: host={} not in cdn-whitelist", host);
        }
        return ok;
    }

    public static boolean isBilibiliCdnHost(String host) {
        // 判定逻辑单一事实源：common/utils/BiliCdnHosts（原先三处镜像实现已收敛）
        return com.vibemusic.common.utils.BiliCdnHosts.isAudioCdnHost(host);
    }

    /** 代理请求头构造：仅 B 站 CDN 附加 Referer，其他域行为不变。 */
    public static void applyProxyHeaders(HttpHeaders headers, String host, String rangeHeader) {
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        if (rangeHeader != null) headers.set("Range", rangeHeader);
        if (isBilibiliCdnHost(host)) headers.set("Referer", BILIBILI_REFERER);
    }

    /**
     * 从上游 CDN 直连转发音频字节（支持 Range、403/404 换链重试、总预算熔断）。
     *
     * @param asAttachment     true=浏览器下载（补 {@code Content-Disposition: attachment}）
     * @param downloadFileName 下载文件名（不含扩展名也可，缺省用 sourceId）
     */
    public void proxy(String sourceId, String name, String artist, String platform,
                      HttpServletRequest request, HttpServletResponse response,
                      boolean asAttachment, String downloadFileName) {
        if (asAttachment) {
            applyAttachmentHeaders(response, sourceId, downloadFileName);
        }

        final int MAX_ATTEMPTS = 5;
        final long deadline = System.currentTimeMillis() + STREAM_TOTAL_BUDGET_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String audioUrl = songPlayService.getPlayUrl(sourceId, name, artist, platform);
                if (audioUrl == null) {
                    if (canRetryStream(attempt, MAX_ATTEMPTS, deadline, System.currentTimeMillis())) {
                        log.info("streamFromRemote: playUrl=null, retry {}/{}: sourceId={}",
                                attempt, MAX_ATTEMPTS, sourceId);
                        continue;
                    }
                    log.info("streamFromRemote: playUrl=null 且预算耗尽/次数用尽 → 404: sourceId={}", sourceId);
                    if (!response.isCommitted()) response.setStatus(404);
                    return;
                }
                // 换源命中时补一个新增响应头（不改任何现有头/响应体/{code,message,data} 信封）：
                // 前端据此可提示「已自动切换到 <平台> 源」。一次性消费，避免线程复用串请求。
                String failoverSource = songPlayService.consumeFailoverPlaySource();
                if (failoverSource != null && !response.isCommitted()) {
                    response.setHeader("X-Play-Source", failoverSource);
                }
                String host = URI.create(audioUrl).getHost();
                if (!isCdnWhitelisted(host)) {
                    log.warn("SSRF blocked: {} (sourceId={})", host, sourceId);
                    response.setStatus(403); return;
                }
                String rangeHeader = request != null ? request.getHeader("Range") : null;
                restClient.get().uri(URI.create(audioUrl)).headers(h -> {
                    applyProxyHeaders(h, host, rangeHeader);
                }).exchange((clientReq, clientResp) -> {
                    int cdnStatus = clientResp.getStatusCode().value();
                    // CDN URL 过期 (403) 或资源不存在 (404) → 作废短缓存并抛异常触发重试获取新 URL。
                    // 必须先作废：否则 5 次重试会反复命中同一个死 URL，把可自愈路径变成必死 500。
                    if (cdnStatus == 403 || cdnStatus == 404) {
                        songPlayService.invalidatePlayUrl(sourceId, platform);
                        throw new RuntimeException("CDN returned " + cdnStatus + ", will retry with fresh URL");
                    }
                    String ct = clientResp.getHeaders().getFirst("Content-Type");
                    response.setContentType(ct != null && ct.startsWith("audio/") ? ct : "audio/mpeg");
                    long cl = clientResp.getHeaders().getContentLength();
                    if (cl > 0) response.setContentLength((int) cl);
                    response.setHeader("Accept-Ranges", "bytes");
                    response.setHeader("Cache-Control", "private, max-age=3600");
                    if (clientResp.getStatusCode().is2xxSuccessful()) {
                        response.setStatus(cdnStatus);
                        if (cdnStatus == 206) {
                            String cr = clientResp.getHeaders().getFirst("Content-Range");
                            if (cr != null) response.setHeader("Content-Range", cr);
                        }
                    }
                    try (InputStream in = clientResp.getBody();
                         OutputStream out = response.getOutputStream()) { StreamUtils.copy(in, out); }
                    return null;
                });
                return;
            } catch (Exception e) {
                boolean last = !canRetryStream(attempt, MAX_ATTEMPTS, deadline, System.currentTimeMillis());
                if (!last && !response.isCommitted()) {
                    log.warn("音频流代理重试 ({}/{}) sourceId={}: {}", attempt, MAX_ATTEMPTS, sourceId, e.getMessage());
                } else {
                    if (!response.isCommitted()) {
                        response.setStatus(500);
                        log.error("音频流代理最终失败 sourceId={}: {}", sourceId, e.getMessage());
                    }
                    return;   // 终局：不再空转
                }
            }
        }
    }

    /** 下载场景：必须在写字节前落 Content-Disposition，否则浏览器按内联处理。 */
    private void applyAttachmentHeaders(HttpServletResponse response, String sourceId, String downloadFileName) {
        String fileName = downloadFileName;
        if (fileName == null || fileName.isBlank()) fileName = sourceId;
        if (!fileName.toLowerCase().endsWith(".mp3")) fileName = fileName + ".mp3";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.setContentType("audio/mpeg");
    }
}
