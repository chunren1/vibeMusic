package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiCallBudget} 纯逻辑单测（可拨动假时钟，绝不 sleep 真实 45s）。
 * <p>
 * 关注点：剩余预算递减、单次超时截断、耗尽后拒绝新调用。
 */
@DisplayName("AiCallBudget AI 调用总预算")
class AiCallBudgetTest {

    /** 可拨动的假时钟：只改 instant，不真等。 */
    private static final class MutableClock extends Clock {
        private static final ZoneId ZONE = ZoneId.of("UTC");
        private Instant now = Instant.parse("2026-10-23T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("剩余预算随时间流逝单调递减，且永不为负")
    void remainingShouldDecreaseAndNeverGoNegative() {
        MutableClock clock = new MutableClock();
        AiCallBudget budget = AiCallBudget.start(Duration.ofSeconds(45), Duration.ofSeconds(60), clock);

        assertEquals(45_000L, budget.remainingMillis());
        assertTrue(budget.canCall());

        clock.advance(Duration.ofSeconds(10));
        assertEquals(35_000L, budget.remainingMillis());
        assertEquals(10_000L, budget.elapsedMillis());

        clock.advance(Duration.ofSeconds(60));
        assertEquals(0L, budget.remainingMillis(), "剩余预算不得为负");
        assertEquals(45_000L, budget.elapsedMillis(), "已消耗不得超过总预算");
        assertFalse(budget.canCall());
    }

    @Test
    @DisplayName("单次超时 = min(单次上限, 剩余预算)：先被总预算截断")
    void timeoutShouldBeCappedByTotalBudget() {
        MutableClock clock = new MutableClock();
        // 单次上限 60s 大于总预算 45s：第一次调用最多只能拿 45s
        AiCallBudget budget = AiCallBudget.start(Duration.ofSeconds(45), Duration.ofSeconds(60), clock);
        assertEquals(Duration.ofSeconds(45), budget.timeoutForNextCall());

        clock.advance(Duration.ofSeconds(40));
        assertEquals(Duration.ofSeconds(5), budget.timeoutForNextCall(), "剩余 5s 时续轮只能拿 5s");
    }

    @Test
    @DisplayName("单次超时 = min(单次上限, 剩余预算)：单次上限更小时以它为准")
    void timeoutShouldBeCappedBySingleCallLimit() {
        MutableClock clock = new MutableClock();
        AiCallBudget budget = AiCallBudget.start(Duration.ofSeconds(45), Duration.ofSeconds(3), clock);
        assertEquals(Duration.ofSeconds(3), budget.timeoutForNextCall());
    }

    @Test
    @DisplayName("预算耗尽后 canCall=false，且拒绝再计算超时")
    void exhaustedBudgetShouldRefuseFurtherCalls() {
        MutableClock clock = new MutableClock();
        AiCallBudget budget = AiCallBudget.start(Duration.ofMillis(500), Duration.ofSeconds(60), clock);

        clock.advance(Duration.ofMillis(499));
        assertTrue(budget.canCall());
        assertEquals(Duration.ofMillis(1), budget.timeoutForNextCall());

        clock.advance(Duration.ofMillis(1));
        assertFalse(budget.canCall());
        assertThrows(IllegalStateException.class, budget::timeoutForNextCall);
    }

    @Test
    @DisplayName("默认预算 45s / 单次上限 60s（3×60s=180s 收敛为 45s）")
    void defaultsShouldMatchDesign() {
        assertEquals(Duration.ofSeconds(45), AiCallBudget.DEFAULT_TOTAL_BUDGET);
        assertEquals(Duration.ofSeconds(60), AiCallBudget.DEFAULT_SINGLE_CALL_LIMIT);
        assertTrue(AiCallBudget.DEFAULT_TOTAL_BUDGET.compareTo(AiCallBudget.DEFAULT_SINGLE_CALL_LIMIT) < 0);
    }

    @Test
    @DisplayName("非正预算直接拒绝，避免拿到 0 超时去外呼")
    void shouldRejectNonPositiveBudget() {
        MutableClock clock = new MutableClock();
        assertThrows(IllegalArgumentException.class,
                () -> AiCallBudget.start(Duration.ZERO, Duration.ofSeconds(60), clock));
        assertThrows(IllegalArgumentException.class,
                () -> AiCallBudget.start(Duration.ofSeconds(-1), Duration.ofSeconds(60), clock));
        assertThrows(IllegalArgumentException.class,
                () -> AiCallBudget.start(Duration.ofSeconds(45), Duration.ZERO, clock));
    }

    @Test
    @DisplayName("shorter 取较小值（容忍 null）")
    void shorterShouldPickSmaller() {
        assertEquals(Duration.ofSeconds(3),
                AiCallBudget.shorter(Duration.ofSeconds(3), Duration.ofSeconds(20)));
        assertEquals(Duration.ofSeconds(3),
                AiCallBudget.shorter(null, Duration.ofSeconds(3)));
        assertEquals(Duration.ofSeconds(20),
                AiCallBudget.shorter(Duration.ofSeconds(20), null));
    }
}
