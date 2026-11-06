package com.vibemusic.service;

import com.vibemusic.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 注册防滥用闸门（2026-11-06）。
 *
 * <p>背景：注册接口此前没有任何验证——不校验用户名形态、不限次数、无邀请码。
 * 而 {@code RateLimitFilter} 的计数键是 {@code user:<id>}（见 {@code RequestIdentity}），
 * 意味着<b>每注册一个新账号就换一份新额度</b>（search 20/min、stream 60/min）。
 * 批量注册等于绕开全部按账号限流：搜索一次扇出 5 个上游平台，stream 是纯音频代理，
 * 既是本站流量也是上游成本。故这里补三道零成本的闸：</p>
 *
 * <ol>
 *   <li><b>用户名字符集</b>：字母/数字/下划线，3-30 位。此前只校验「长度 ≤30」，
 *       中文、空格、emoji、同形字都能注册，既污染展示也让人眼难辨真假账号。</li>
 *   <li><b>邀请码</b>：{@code REGISTER_INVITE_CODE} 非空时强制校验；留空则整条规则不生效
 *       （便于「先发后端、等 App 带输入框的版本上线后再启用」，避免存量客户端无法注册）。</li>
 *   <li><b>每网络每日上限</b>：同一个出口 IP 每天最多注册 {@code REGISTER_DAILY_LIMIT_PER_IP}
 *       次（默认 3），Redis INCR + 24h TTL。IPv6 按 /64 聚合——否则一个 /64 相当于
 *       2^64 个地址，等于没有上限。</li>
 * </ol>
 *
 * <p>顺序刻意是「形态 → 邀请码 → 配额」：填错邀请码不应该烧掉当天的注册配额，
 * 邀请码爆破本身还有 nginx 的 {@code limit_req zone=auth}（10r/m）兜底。</p>
 *
 * <p>fail-open：配额依赖 Redis，{@link RateLimitService#tryAcquire} 在 Redis 异常时放行。
 * 这里沿用同一口径——注册是低频人工操作，宁可漏拦也不能因为缓存抖动把新用户挡在门外。</p>
 */
@Slf4j
@Component
public class RegistrationGuard {

    /** 用户名白名单：字母/数字/下划线，3-30 位（前端提示文案与此保持一致）。 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,30}$");

    /** 限流键片段白名单：防止任意超长/控制字符污染 Redis 键空间。 */
    private static final Pattern SAFE_IP = Pattern.compile("[A-Za-z0-9.:]{1,64}");

    private static final Duration QUOTA_WINDOW = Duration.ofDays(1);

    private final RateLimitService rateLimitService;
    private final String inviteCode;
    private final int dailyLimitPerIp;

    public RegistrationGuard(RateLimitService rateLimitService,
                             @Value("${auth.register.invite-code:}") String inviteCode,
                             @Value("${auth.register.daily-limit-per-ip:3}") int dailyLimitPerIp) {
        this.rateLimitService = rateLimitService;
        this.inviteCode = inviteCode == null ? "" : inviteCode.trim();
        this.dailyLimitPerIp = dailyLimitPerIp;
    }

    /** 邀请码是否已启用（启用后存量客户端必须带 code 才能注册）。 */
    public boolean isInviteCodeRequired() {
        return !inviteCode.isEmpty();
    }

    /**
     * 用户名字符集校验。只做「形态」判断，长度上限仍由 DTO 的 Bean Validation 负责。
     *
     * @param username 调用方已 trim 过的用户名
     * @throws BusinessException 400
     */
    public void validateUsername(String username) {
        if (username != null && !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(400, "用户名只能包含字母、数字、下划线，3-30 位");
        }
    }

    /**
     * 邀请码校验。未配置邀请码时直接放行（向后兼容）。
     *
     * @throws BusinessException 403
     */
    public void validateInviteCode(String submitted) {
        if (!isInviteCodeRequired()) {
            return;
        }
        String value = submitted == null ? "" : submitted.trim();
        if (!inviteCode.equals(value)) {
            throw new BusinessException(403, "邀请码不正确");
        }
    }

    /**
     * 消耗该来源网络当天的注册配额。
     *
     * @throws BusinessException 429（带剩余小时数，便于前端提示）
     */
    public void consumeQuota(HttpServletRequest request) {
        if (dailyLimitPerIp <= 0) {
            return;
        }
        String key = quotaKey(request);
        if (rateLimitService.tryAcquire(key, dailyLimitPerIp, QUOTA_WINDOW)) {
            return;
        }
        long remaining = rateLimitService.remainingSeconds(key, QUOTA_WINDOW.getSeconds());
        long hours = Math.max(1, (remaining + 3599) / 3600);
        throw new BusinessException(429, "该网络今日注册次数已达上限（" + dailyLimitPerIp + " 次），约 " + hours + " 小时后恢复");
    }

    /**
     * 配额键：{@code register:ip:<ip>}；IPv6 归并到 /64 前缀。
     * <p>IP 取 {@code X-Real-IP}（nginx 用 {@code $remote_addr} 覆盖写入，CF 真实 IP 已由
     * {@code cloudflare-realip.conf} 还原），缺失时回落 {@code remoteAddr}。</p>
     */
    private String quotaKey(HttpServletRequest request) {
        String ip = request == null ? null : request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            ip = request == null ? null : request.getRemoteAddr();
        }
        ip = ip == null ? "" : ip.trim();
        if (!SAFE_IP.matcher(ip).matches()) {
            ip = "unknown";
        }
        if (ip.indexOf(':') >= 0) {
            ip = ipv6Prefix(ip);
        }
        return "register:ip:" + ip;
    }

    /** 取 IPv6 前 4 组 hextet 作为 /64 前缀，非标准写法原样兜底。 */
    static String ipv6Prefix(String ip) {
        String[] parts = ip.split(":", -1);
        if (parts.length < 4) {
            return ip;
        }
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 4; i++) {
            sb.append(parts[i]).append(':');
        }
        return sb.append("/64").toString();
    }
}
