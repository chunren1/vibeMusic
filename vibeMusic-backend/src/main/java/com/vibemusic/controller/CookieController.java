package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方 Cookie 绑定（BYOC：Bring Your Own Cookie）。
 *
 * <p>只收发绑定元数据，绝不回显、不记录 Cookie 本体。
 * 身份经 {@link UserService#getCurrentUserId()} 获取；SecurityConfig 默认
 * {@code authenticated()} 已覆盖 {@code /api/cookies/**}，无需白名单。
 *
 * <p>状态契约（{@code GET /api/cookies/status → data.netease}）：
 * {@code {has, valid, updatedAt, needsRebind}}，其中
 * <ul>
 *   <li>{@code has}：是否已绑定（密文+IV 均存在）；</li>
 *   <li>{@code valid}：有效性，null=未知（刚绑定/未探测），true=有效，false=失效；</li>
 *   <li>{@code updatedAt}：最近绑定时间，null=从未绑定；</li>
 *   <li>{@code needsRebind}：是否需要重绑，{@code has && valid == false} 时为 true。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/cookies")
@RequiredArgsConstructor
@Tag(name = "Cookie", description = "第三方 Cookie 绑定（仅元数据回显）")
public class CookieController {

    private final UserService userService;

    /** 网易云 Cookie 明文上限（字符数，与 UserService 服务端校验保持一致）。 */
    private static final int NETEASE_COOKIE_MAX_CHARS = 8192;

    @PutMapping("/netease")
    @Operation(summary = "绑定网易云 Cookie")
    public Result<Map<String, Object>> bindNetease(@RequestBody(required = false) Map<String, String> body) {
        Long userId = requireLogin();
        String cookie = body == null ? null : body.get("cookie");
        if (cookie == null || cookie.isBlank()) {
            throw new BusinessException(400, "Cookie 不能为空");
        }
        String trimmed = cookie.strip();
        if (trimmed.length() > NETEASE_COOKIE_MAX_CHARS) {
            throw new BusinessException(400, "Cookie 过长，请重新粘贴完整 Cookie 并绑定");
        }
        if (!trimmed.contains("MUSIC_U=")) {
            throw new BusinessException(400, "Cookie 缺少 MUSIC_U，请重新粘贴完整 Cookie 并绑定");
        }
        userService.saveNeteaseCookie(userId, trimmed);
        // 红线：只记长度，绝不记内容
        log.info("网易云 Cookie 已绑定: userId={}, cookieChars={}", userId, trimmed.length());
        return Result.ok(Map.of("bound", true));
    }

    @DeleteMapping("/netease")
    @Operation(summary = "解绑网易云 Cookie")
    public Result<Map<String, Object>> unbindNetease() {
        Long userId = requireLogin();
        userService.clearNeteaseCookie(userId);
        log.info("网易云 Cookie 已解绑: userId={}", userId);
        return Result.ok(Map.of("bound", false));
    }

    @GetMapping("/status")
    @Operation(summary = "查询 Cookie 绑定状态（仅布尔与时间戳）")
    public Result<Map<String, Object>> status() {
        Long userId = requireLogin();
        UserService.NeteaseCookieStatus netease = userService.getNeteaseCookieStatus(userId);
        Map<String, Object> neteaseMeta = new LinkedHashMap<>();
        neteaseMeta.put("has", netease.has());
        neteaseMeta.put("valid", netease.valid());
        neteaseMeta.put("updatedAt", netease.updatedAt());
        neteaseMeta.put("needsRebind", netease.has() && Boolean.FALSE.equals(netease.valid()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("netease", neteaseMeta);
        data.put("bili", Map.of("reserved", true));
        return Result.ok(data);
    }

    private static Long requireLogin() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        return userId;
    }
}
