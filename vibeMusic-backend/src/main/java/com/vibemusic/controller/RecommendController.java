package com.vibemusic.controller;

import com.vibemusic.common.Result;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.service.RecommendService;
import com.vibemusic.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;

    /**
     * 个性化推荐
     * 已登录用户基于播放历史生成推荐，未登录用户返回随机推荐
     */
    @GetMapping("/personalized")
    @Operation(summary = "个性化推荐歌曲")
    public Result<RecommendResult> personalized(
            @RequestHeader(value = "X-Device-Id", required = false) String headerDeviceId,
            @RequestParam(value = "deviceId", required = false) String paramDeviceId,
            HttpServletRequest request) {

        // 优先从 JWT 获取 userId
        Long userId = UserService.getCurrentUserId();

        // 未登录时用 deviceId 做缓存隔离
        String deviceId = userId == null
                ? (paramDeviceId != null ? paramDeviceId : headerDeviceId)
                : null;

        log.debug("推荐请求: userId={}, deviceId={}", userId, deviceId);
        RecommendResult result = recommendService.getPersonalized(userId, deviceId);
        return Result.ok(result);
    }
}
