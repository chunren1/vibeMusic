package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.FavoriteService;
import com.vibemusic.service.IdempotentGuard;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/favorites")
@RequiredArgsConstructor
@Tag(name = "收藏", description = "歌曲收藏")
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final IdempotentGuard idempotentGuard;

    /** 切换收藏（幂等：X-Request-Id 5分钟防重放） */
    @PostMapping("/toggle")
    @Operation(summary = "收藏/取消收藏")
    public Result<Boolean> toggle(@RequestBody Map<String, Object> body,
                                  HttpServletRequest request) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");

        // 幂等检查：5 分钟内相同 Request-Id 直接返回成功
        String requestId = request.getHeader("X-Request-Id");
        if (!idempotentGuard.tryAcquire(requestId)) {
            return Result.ok("重复请求已忽略", true);
        }

        String sourceId = (String) body.get("sourceId");
        String songName = (String) body.get("songName");
        String artist = (String) body.get("artist");
        String coverUrl = (String) body.getOrDefault("coverUrl", "");

        // Bug4修复: 必填字段校验
        if (sourceId == null || sourceId.isEmpty()) return Result.error("sourceId 不能为空");
        if (songName == null || songName.isEmpty()) return Result.error("songName 不能为空");

        boolean faved = favoriteService.toggle(userId, sourceId, songName,
                artist != null ? artist : "未知歌手", coverUrl);
        return Result.ok(faved ? "已收藏" : "已取消", faved);
    }

    /** 我的收藏列表 */
    @GetMapping("/list")
    @Operation(summary = "我的收藏列表")
    public Result<List<Map<String, Object>>> list(@RequestParam(defaultValue = "50") int count) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.ok(favoriteService.list(userId, count));
    }

    /** 获取收藏的 sourceId 集合（前端高亮用） */
    @GetMapping("/ids")
    @Operation(summary = "获取收藏的歌曲ID集合")
    public Result<Set<String>> ids() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.ok(Set.of());
        return Result.ok(favoriteService.favoritesSet(userId));
    }
}
