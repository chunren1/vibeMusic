package com.vibemusic.task;

import com.vibemusic.service.ESSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * ES 过期数据定时清理 — 每小时删除 1 小时前的缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ESCleanupTask {

    private final ESSearchService esSearchService;

    @Scheduled(cron = "0 0 * * * *") // 每小时整点执行
    public void cleanExpiredCache() {
        Date expireTime = Date.from(Instant.now().minus(1, ChronoUnit.HOURS));
        log.info("定时清理 ES 过期缓存: before {}", expireTime);
        esSearchService.deleteByCreateTimeBefore(expireTime);
    }
}
