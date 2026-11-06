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
        // 416 自愈标记（lambda 内需 effectively final，故用单元素数组）：
        // 首次 416 先换地址重试一次；第二次仍是 416 才原样透传，避免无限重取。
        final boolean[] refreshedAfter416 = {false};
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

                    // 416 = 请求的 Range 超出资源长度。两类来源必须分开处理：
                    // ① 客户端 position 真越界（暂停数小时后恢复）——URL 有效，重取无用；
                    // ② 同一首歌的文件在两次取链之间变了（码率降档 / VIP 失效 / 文件替换）——
                    //    客户端按旧长度请求，只有换一个新地址才可能恢复。
                    // 2026-11-06 线上观测：99 次 416 集中在两首歌，客户端每 2 秒重试一次、
                    // 持续两分钟不自愈。故首次 416 先作废地址重取一次，仍 416 再原样透传。
                    // 无论哪种，都不能把 416 伪装成 200：Media3 会按「200 + 非零 position」
                    // 手动 skip，撞上 CDN 错误页立即 EOF → ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE。
                    if (cdnStatus == 416) {
                        String upstreamCr = clientResp.getHeaders().getFirst("Content-Range");
                        // 这条日志是定位"到底请求到了哪个字节"的唯一线索：
                        // clientRange 是客户端认为的位置，upstreamContentRange 是上游认为的长度。
                        log.warn("stream 416: sourceId={} platform={} clientRange={} upstreamContentRange={} host={} retried={}",
                                sourceId, platform, rangeHeader, upstreamCr, host, refreshedAfter416[0]);
                        if (!refreshedAfter416[0]) {
                            refreshedAfter416[0] = true;
                            songPlayService.invalidatePlayUrl(sourceId, platform);
                            throw new RuntimeException("CDN 416, refresh play url and retry once");
                        }
                        response.setStatus(416);
                        String cr = contentRangeForUnsatisfied(upstreamCr);
                        if (cr != null) response.setHeader("Content-Range", cr);
                        response.setHeader("Accept-Ranges", "bytes");
                        // 刻意不写 Content-Length、不转发 CDN 的错误页 body
                        return null;
                    }

                    // 其它非 2xx（403/404 死链、400/-460、5xx 上游抖动）→ 作废短缓存并抛异常换新 URL。
                    // 必须先作废：否则 5 次重试会反复命中同一个死 URL，把可自愈路径变成必死。
                    // 也绝不允许把错误页当音频以 200 转发出去。
                    if (!clientResp.getStatusCode().is2xxSuccessful()) {
                        songPlayService.invalidatePlayUrl(sourceId, platform);
                        throw new RuntimeException("CDN returned " + cdnStatus + ", will retry with fresh URL");
                    }

                    String ct = clientResp.getHeaders().getFirst("Content-Type");
                    response.setContentType(ct != null && ct.startsWith("audio/") ? ct : "audio/mpeg");
                    // 只有确认转发 2xx 时才声明长度（setContentLengthLong 防 long→int 截断）
                    long cl = clientResp.getHeaders().getContentLength();
                    if (cl > 0) {
                        response.setContentLengthLong(cl);
                        if (cdnStatus == 200 && rangeHeader == null) {
                            log.info("stream 200 full: sourceId={} platform={} len={}", sourceId, platform, cl);
                        }
                    }
                    response.setHeader("Accept-Ranges", "bytes");
                    response.setHeader("Cache-Control", "private, max-age=3600");
                    response.setStatus(cdnStatus);
                    if (cdnStatus == 206) {
                        String cr = clientResp.getHeaders().getFirst("Content-Range");
                        if (cr != null) {
                            response.setHeader("Content-Range", cr);
                            // 只在从头请求时记总长度：事后可与 416 的 clientRange 对比，
                            // 判断"同一首歌的文件是否在两次取链之间变短过"。
                            if (rangeHeader == null || rangeHeader.startsWith("bytes=0-")) {
                                log.info("stream 206 head: sourceId={} platform={} contentRange={}",
                                        sourceId, platform, cr);
                            }
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
                        // 502 = 上游（CDN）不可用/死链，重试已耗尽；绝不能再以 200 回一个空 body
                        response.setStatus(502);
                        log.error("音频流代理最终失败 sourceId={}: {}", sourceId, e.getMessage());
                    }
                    return;   // 终局：不再空转
                }
            }
        }
    }

    /**
     * 纯函数：构造 416 响应的 {@code Content-Range}。
     *
     * <p>优先透传上游的 unsatisfied-range 形式（{@code bytes *}{@code /2465167}）；若上游给的是
     * {@code bytes 0-0/2465167} 之类，也归一化成 {@code bytes *}{@code /2465167}。
     * 解析不出资源总长度时返回 {@code null}（此时不写该头，绝不拿错误页的 Content-Length 冒充文件长度）。</p>
     */
    static String contentRangeForUnsatisfied(String upstreamContentRange) {
        if (upstreamContentRange == null || upstreamContentRange.isBlank()) return null;
        String value = upstreamContentRange.trim();
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash == value.length() - 1) return null;
        String total = value.substring(slash + 1).trim();
        if (total.isEmpty() || "*".equals(total)) return null;
        return "bytes */" + total;
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
