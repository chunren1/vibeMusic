package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.service.PlayHistoryService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 播放历史控制器 — 查询、导出、删除
 */
@RestController
@RequestMapping("/api/songs/history")
@RequiredArgsConstructor
@Tag(name = "播放历史", description = "播放历史记录管理")
public class PlayHistoryController {

    private final PlayHistoryService playHistoryService;

    @GetMapping
    @Operation(summary = "最近播放列表")
    public Result<List<Map<String, Object>>> history(@RequestParam(defaultValue = "20") int count) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.ok(List.of());
        return Result.ok(playHistoryService.recent(userId, count));
    }

    @GetMapping("/export")
    @Operation(summary = "导出播放历史")
    public Result<Map<String, Object>> export() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.ok(playHistoryService.export(userId));
    }

    @PostMapping("/remove")
    @Operation(summary = "批量删除播放历史")
    public Result<Integer> remove(@RequestBody Map<String, Object> body) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        @SuppressWarnings("unchecked")
        List<String> sourceIds = (List<String>) body.get("sourceIds");
        if (sourceIds == null || sourceIds.isEmpty()) return Result.ok(0);
        int count = playHistoryService.deleteBatch(userId, sourceIds);
        return Result.ok("已删除 " + count + " 条记录", count);
    }
}
