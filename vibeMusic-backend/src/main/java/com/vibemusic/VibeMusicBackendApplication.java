package com.vibemusic;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

@SpringBootApplication
@MapperScan("com.vibemusic.mapper")
@EnableScheduling
public class VibeMusicBackendApplication {

    private static final Logger log = LoggerFactory.getLogger(VibeMusicBackendApplication.class);

    /** 期望的 UTC 偏移：CST(+08:00)。与 mysql 容器保持一致。 */
    private static final ZoneOffset EXPECTED_OFFSET = ZoneOffset.ofHours(8);

    public static void main(String[] args) {
        logStartupTimezone();
        SpringApplication.run(VibeMusicBackendApplication.class, args);
    }

    /**
     * 启动即打印 JVM 实际时区，并在偏移不是 +08:00 时大声告警。
     *
     * <p>背景（2026-10-26 线上事故）：{@code play_history.played_at} / {@code updated_at} 由 Java 的
     * {@code LocalDateTime.now()} 填充，而 {@code created_at} 走 MySQL 的
     * {@code DEFAULT CURRENT_TIMESTAMP}。mysql 容器是 CST，但 backend 容器当时没声明 {@code TZ}（UTC），
     * 于是同一行里两种时间差 8 小时，App「最近播放」时间全错。
     *
     * <p>该告警是"可观察信号"：配置再次漏掉 TZ 时不必等用户报 bug，日志里第一屏就能看到。
     */
    private static void logStartupTimezone() {
        ZoneId zone = ZoneId.systemDefault();
        ZoneOffset offset = zone.getRules().getOffset(Instant.now());
        log.info("JVM 时区 = {} (offset {}), 当前时间 = {}", zone, offset, LocalDateTime.now());
        if (!EXPECTED_OFFSET.equals(offset)) {
            log.warn("JVM 时区偏移不是 {}！play_history.played_at / updated_at 由 LocalDateTime.now() 写入，"
                            + "而 created_at 走 MySQL 的 DEFAULT CURRENT_TIMESTAMP（容器 CST）——"
                            + "两者不一致会让同一行时间差数小时（App「最近播放」显示错乱）。"
                            + "请检查 Dockerfile 的 ENV TZ 与 compose 的 backend.environment.TZ。",
                    EXPECTED_OFFSET);
        }
    }
}
