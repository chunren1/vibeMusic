package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.FavoriteService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    /** 切换收藏 */
    @PostMapping("/toggle")
    @Operation(summary = "收藏/取消收藏")
    public Result<Boolean> toggle(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");

        String sourceId = (String) body.get("sourceId");
        String songName = (String) body.get("songName");
        String artist = (String) body.get("artist");
        String coverUrl = (String) body.getOrDefault("coverUrl", "");

        boolean faved = favoriteService.toggle(userId, sourceId, songName, artist, coverUrl);
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
