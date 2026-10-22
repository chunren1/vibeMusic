package com.vibemusic.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 贵端点限流配置（P0-3）。
 *
 * <p>为什么只管两个端点：搜索一次扇出 5 个上游平台、{@code /stream} 会把后端当成免费音频代理，
 * 是全站仅有的“单请求放大 N 倍成本”入口；其余接口（banner / lyric / 歌单查询）成本低，
 * 再叠一层限流只会增加误伤面。</p>
 *
 * <p>限额按“身份”计数（已登录用户 &gt; 设备 ID &gt; 客户端 IP），不是全局上限。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /** 总开关；排障时可临时关闭（重启生效，也可用 RATE_LIMIT_ENABLED=false）。 */
    private boolean enabled = true;

    /** 计数窗口（秒）。默认 1 分钟。 */
    private int windowSeconds = 60;

    /** 搜索：每身份每窗口最多 20 次（一次搜索会扇出 5 个上游）。 */
    private int searchPerMin = 20;

    /** 音频流：每身份每窗口最多 60 次（含拖拽进度产生的 Range 请求）。 */
    private int streamPerMin = 60;
}
