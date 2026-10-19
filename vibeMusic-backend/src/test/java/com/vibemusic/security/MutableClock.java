package com.vibemusic.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：避免 sleep 造成的慢/闪测试（TTL 边界必须精确验证）。
 */
final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void advanceMillis(long millis) {
        this.instant = this.instant.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
