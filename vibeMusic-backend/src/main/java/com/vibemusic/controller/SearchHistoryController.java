package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.SearchHistorySyncRequest;
import com.vibemusic.service.SearchHistoryService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 搜索历史云同步 — App 本地 DataStore（cap 10）与云端（cap 50）对齐。
 * 非白名单路径，SecurityConfig 要求 JWT；Controller 层二次校验兜底。
 */
@RestController
@RequestMapping("/api/search/history")
@RequiredArgsConstructor
@Tag(name = "搜索历史", description = "搜索历史云同步")
public class SearchHistoryController {

    private final SearchHistoryService searchHistoryService;

    @GetMapping
    @Operation(summary = "拉取搜索历史（云同步 pull）")
    public Result<List<String>> pull(@RequestParam(defaultValue = "20") int count) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        return Result.ok(searchHistoryService.list(userId, count));
    }

    @PostMapping("/sync")
    @Operation(summary = "推送搜索历史（云同步 push，upsert + 裁剪）")
    public Result<List<String>> push(@Valid @RequestBody SearchHistorySyncRequest req) {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        List<String> keywords = req == null ? null : req.keywords();
        if (keywords == null) return Result.error("keywords 不能为空");
        return Result.ok(searchHistoryService.push(userId, keywords));
    }

    @DeleteMapping
    @Operation(summary = "清空搜索历史")
    public Result<Integer> clear() {
        Long userId = UserService.getCurrentUserId();
        if (userId == null) return Result.error(401, "请先登录");
        int count = searchHistoryService.clear(userId);
        return Result.ok("已清空 " + count + " 条", count);
    }
}
