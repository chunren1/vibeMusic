package com.vibemusic.common.utils;

import com.vibemusic.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.regex.Pattern;

/**
 * 限流身份解析（P0-3）：把一次请求折算成一个稳定的计数键片段。
 *
 * <p>优先级：<b>已登录用户</b> &gt; <b>X-Device-Id</b>（App 生成）
 * &gt; <b>X-Real-IP</b>（nginx 设置）&gt; <b>remoteAddr</b>。
 * 刻意<b>不采信</b> {@code X-User-Id}：出站时后端自己会把它写给网关，入站方向它同样可被客户端伪造，
 * 采信等于给攻击者一个“换头即换额度”的后门。</p>
 *
 * <p>为什么允许设备 ID：App 端设备 ID 是本机生成并持久化的，同一台设备换 WiFi 后计数仍然连续，
 * 比纯 IP 更贴近“一个用户”。代价是它可以被伪造——但伪造者只是在花自己的额度，
 * 且 IP 维度还有 nginx 的 {@code limit_req} 兜底（伪造设备 ID 换不来无限额度，只能换来同等份额）。</p>
 *
 * <p>所有外部输入都做长度与字符白名单校验：限流键会进 Redis，不能放任任意超长/控制字符污染键空间。</p>
 */
public final class RequestIdentity {

    /** 身份片段白名单：字母数字 + . _ - @ :，最长 64。冒号用于 user:/anon: 前缀。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._@:-]{1,64}");

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_DEVICE_ID = "X-Device-Id";
    private static final String HEADER_REAL_IP = "X-Real-IP";

    private RequestIdentity() {
    }

    /**
     * @return 形如 {@code user:12} / {@code dev:ab12...} / {@code ip:1.2.3.4}，绝不会为空
     */
    public static String resolve(HttpServletRequest request) {
        Long userId = currentUserId();
        if (userId != null) {
            return "user:" + userId;
        }
        String deviceId = safeHeader(request, HEADER_DEVICE_ID);
        if (deviceId != null) {
            return "dev:" + deviceId;
        }
        String realIp = safeHeader(request, HEADER_REAL_IP);
        if (realIp != null) {
            return "ip:" + realIp;
        }
        String remote = request == null ? null : request.getRemoteAddr();
        return "ip:" + (isSafe(remote) ? remote : "unknown");
    }

    /**
     * 已认证用户 ID；未登录返回 null。用 SecurityContext 而不是控制器参数，
     * 因为限流器跑在 Filter 链里，拿不到方法参数。
     */
    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails details) {
            return details.getUserId();
        }
        return null;
    }

    /** 取头值并校验；非法（超长/含非法字符）视为缺失，避免被伪造头撑爆键空间。 */
    private static String safeHeader(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        String value = request.getHeader(name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    private static boolean isSafe(String value) {
        return value != null && SAFE_ID.matcher(value.trim()).matches();
    }
}
