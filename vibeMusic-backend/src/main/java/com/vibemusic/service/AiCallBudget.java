package com.vibemusic.service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 单次 AI 请求的「总预算」（纯逻辑，无 Spring 依赖，时钟可注入）。
 * <p>
 * 背景：Function Calling 最多 3 次 LLM 调用，每次客户端超时 60s
 * （{@code RestTemplateConfig.AI_RESPONSE_TIMEOUT}），单请求最坏 3×60s=180s——
 * 客户端早已断开，容器线程和连接却还被占着。因此一个请求只给一份总预算：
 * 每次调用的超时 = {@code min(单次上限, 剩余预算)}，剩余预算 ≤ 0 时不再发起任何新调用。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>不可变：{@code start()} 时把 deadline 固定下来，之后只读；</li>
 *   <li>时钟注入：单测用可拨动的 {@link Clock} 断言「时间流逝」，不 sleep 真实 45s；</li>
 *   <li>剩余时间永不为负：避免负 Duration 传进 HTTP 客户端导致 IllegalArgumentException。</li>
 * </ul>
 */
public final class AiCallBudget {

    /** 默认总预算 45s（可用 {@code ai.total-budget-ms} 覆盖）。 */
    public static final Duration DEFAULT_TOTAL_BUDGET = Duration.ofSeconds(45);

    /** 默认单次调用上限 60s，与 {@code RestTemplateConfig.AI_RESPONSE_TIMEOUT} 保持一致。 */
    public static final Duration DEFAULT_SINGLE_CALL_LIMIT = Duration.ofSeconds(60);

    private final Clock clock;
    private final long startedAtMillis;
    private final long deadlineMillis;
    private final Duration singleCallLimit;

    private AiCallBudget(Clock clock, long startedAtMillis, long deadlineMillis, Duration singleCallLimit) {
        this.clock = clock;
        this.startedAtMillis = startedAtMillis;
        this.deadlineMillis = deadlineMillis;
        this.singleCallLimit = singleCallLimit;
    }

    /** 用系统时钟开一份预算。 */
    public static AiCallBudget start(Duration totalBudget, Duration singleCallLimit) {
        return start(totalBudget, singleCallLimit, Clock.systemUTC());
    }

    /**
     * 开一份预算。
     *
     * @param totalBudget     本次请求的总预算（必须为正）
     * @param singleCallLimit 单次 LLM 调用上限（与总预算取小）
     * @param clock           时钟（单测注入可拨动的假时钟）
     */
    public static AiCallBudget start(Duration totalBudget, Duration singleCallLimit, Clock clock) {
        Objects.requireNonNull(totalBudget, "totalBudget 不能为 null");
        Objects.requireNonNull(singleCallLimit, "singleCallLimit 不能为 null");
        Objects.requireNonNull(clock, "clock 不能为 null");
        if (totalBudget.isZero() || totalBudget.isNegative()) {
            throw new IllegalArgumentException("AI 总预算必须为正: " + totalBudget);
        }
        if (singleCallLimit.isZero() || singleCallLimit.isNegative()) {
            throw new IllegalArgumentException("单次调用上限必须为正: " + singleCallLimit);
        }
        long startedAt = clock.millis();
        return new AiCallBudget(clock, startedAt, startedAt + totalBudget.toMillis(), singleCallLimit);
    }

    /** 本次请求的总预算（ms）。 */
    public long totalMillis() {
        return deadlineMillis - startedAtMillis;
    }

    /** 已消耗的预算（ms）。 */
    public long elapsedMillis() {
        return Math.max(0, Math.min(totalMillis(), clock.millis() - startedAtMillis));
    }

    /** 剩余预算（ms），永不为负；耗尽即 0。 */
    public long remainingMillis() {
        return Math.max(0, deadlineMillis - clock.millis());
    }

    /** 是否还能发起下一次调用（LLM 或工具）。 */
    public boolean canCall() {
        return remainingMillis() > 0;
    }

    /**
     * 下一次调用可用的超时 = {@code min(单次上限, 剩余预算)}。
     *
     * @throws IllegalStateException 预算已耗尽（调用方应先判 {@link #canCall()}）
     */
    public Duration timeoutForNextCall() {
        long remaining = remainingMillis();
        if (remaining <= 0) {
            throw new IllegalStateException("AI 调用预算已耗尽: totalMs=" + totalMillis());
        }
        return Duration.ofMillis(Math.min(singleCallLimit.toMillis(), remaining));
    }

    /** 取两个时长的较小值（调用方常用于「预算 vs 业务上限」）。 */
    public static Duration shorter(Duration a, Duration b) {
        return a == null ? b : (b == null ? a : (a.compareTo(b) <= 0 ? a : b));
    }
}
