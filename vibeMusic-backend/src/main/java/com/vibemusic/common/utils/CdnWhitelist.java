package com.vibemusic.common.utils;

import java.util.Arrays;
import java.util.List;

/**
 * 音频 CDN 白名单单一匹配器（Q2d 收敛）。
 *
 * <p>生效清单唯一来源 = application.yml {@code stream.cdn-whitelist} 默认值
 * （{@code STREAM_CDN_WHITELIST} 环境变量可覆盖）；
 * {@code StreamController} 与 {@code NeteaseApiService} 的 {@code @Value} 均为
 * 无默认值引用式（缺失即启动失败），匹配算法只此一份，杜绝双份实现漂移。
 */
public final class CdnWhitelist {

    private CdnWhitelist() {
    }

    /** 按逗号拆分白名单配置并逐项 trim，空项丢弃。 */
    public static List<String> parse(String config) {
        if (config == null) {
            return List.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * host 是否命中白名单：{@code *.xxx.com} 匹配其自身与所有子域，精确条目精确匹配。
     *
     * <p>语义与收敛前两处旧实现逐字一致（原样搬运，不做"顺手修复"）。
     */
    public static boolean matches(String host, List<String> whitelist) {
        if (host == null || whitelist == null) {
            return false;
        }
        for (String pattern : whitelist) {
            if (pattern.startsWith("*.")) {
                String suffix = pattern.substring(1);
                if (host.equals(pattern.substring(2)) || host.endsWith(suffix)) {
                    return true;
                }
            } else if (host.equals(pattern)) {
                return true;
            }
        }
        return false;
    }
}
