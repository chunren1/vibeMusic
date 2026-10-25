package com.vibemusic.controller;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * AI 助手请求身份与准入判定（从 AssistantController 抽出，行为不变）。
 *
 * <p>全部为纯函数/常量：不依赖 Spring 上下文，不持有状态，不抛异常。
 * 控制器保留同名 static 委托入口（既有单测直接按类名调用）。
 */
final class AssistantRequestIdentity {

    /** 单用户每分钟配额（同步/流式共用）。 */
    static final int AI_RATE_LIMIT = 10;
    /**
     * 按 IP 的粗粒度兜底限流（防伪造 deviceId 换桶无限刷）。
     * 放宽到远高于单人配额，正常 NAT 出口用户不会触达。
     */
    static final int AI_IP_RATE_LIMIT = 30;

    /** 172.16.0.0/12（docker 网段） */
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    /** 示例/占位 Key：一律视为未配置，绝不外呼（change-me 必然 401，还要白等超时） */
    private static final Set<String> PLACEHOLDER_API_KEYS = Set.of("change-me", "changeme");

    private AssistantRequestIdentity() {
    }

    /**
     * Key 是否可用于外呼：空白 或 占位示例值（change-me / changeme）都视为未配置。
     * <p>占位值不拦截的话，请求会真的打到上游并必然 401——用户看到的是「AI 服务暂时不可用」，
     * 而真实原因是没配 Key，徒增一次外呼与超时。</p>
     */
    static boolean isApiKeyConfigured(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return false;
        return !PLACEHOLDER_API_KEYS.contains(apiKey.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 纯函数：流式会话落库守卫。[DONE] 分支与 onComplete 都会触发"流结束"，
     * 只有「回复非空」且「首次进入」才落库——防止每轮对话在会话记忆里存两份。
     */
    static boolean markPersistedOnce(AtomicBoolean persisted, int replyLength) {
        if (replyLength <= 0) return false;
        return persisted.compareAndSet(false, true);
    }

    /**
     * 粗粒度按 IP 的匿名兜底限流键来源：仅当直连对端是内网（nginx 容器 / 回环）时
     * 才信任 {@code X-Real-IP}（nginx 用 {@code $remote_addr} 覆写，客户端无法伪造），
     * 否则直接用 TCP 对端地址——防止伪造头绕过按 IP 的限流。
     */
    static String clientIp(String remoteAddr, String realIpHeader) {
        if (remoteAddr == null) return "unknown";
        if (isPrivatePeer(remoteAddr) && realIpHeader != null && !realIpHeader.isBlank()) {
            String cleaned = realIpHeader.strip().replaceAll("[^0-9A-Fa-f:.]", "");
            if (!cleaned.isEmpty() && cleaned.length() <= 45) return cleaned;
        }
        return remoteAddr;
    }

    /** 纯函数：是否内网/回环对端（10/8、172.16-31、192.168/16、127.0.0.1、::1）。 */
    static boolean isPrivatePeer(String ip) {
        if (ip == null) return false;
        return ip.equals("127.0.0.1") || ip.equals("::1")
                || ip.startsWith("10.") || ip.startsWith("192.168.")
                || PRIVATE_172.matcher(ip).matches();
    }

    /** 按 IP 兜底限流的 Redis 键（键内只留 IP 允许字符）。 */
    static String ipRateKey(HttpServletRequest request) {
        return "assistant:ip:" + clientIp(request.getRemoteAddr(), request.getHeader("X-Real-IP"));
    }
}
