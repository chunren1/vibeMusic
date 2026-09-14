package com.vibemusic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 歌曲 DTO —— 搜索/推荐返回给前端，Redis 缓存
 * <p>
 * 与 Song 实体的区别：本对象不关联数据库，纯内存对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SongDTO {

    /** 网易云歌曲原始 ID */
    private String sourceId;

    /** 歌曲名 */
    private String name;

    /** 歌手名 */
    private String artist;

    /** 专辑名 */
    private String album;

    /** 封面图 URL */
    private String coverUrl;

    /** 时长（秒） */
    private Integer duration;

    /** 平台来源: netease / qq */
    private String platform;

    /** 可播放的来源平台列表，如 ["netease", "qq"] */
    private java.util.List<String> availableSources;

    /** 跨平台去重后的最终排名分（越高越靠前） */
    private Double finalScore;

    /** MinIO 离线缓存状态（null=未校验，true=已缓存，false=未缓存） */
    private Boolean cached;

    /** VIP/付费状态（从 musicapi 的 fee/pay_play 字段解析） */
    private Boolean vip;

    /**
     * B站播放量（网关 _raw.play 透传；其他平台/缓存回填时为 null）。
     * 加性字段（2026-09 B站质量 pass 新增）：仅供播放量门控排序使用，
     * 不参与 ES 索引与去重键；老缓存反序列化缺失即为 null，语义=未知。
     */
    private Long playCount;

    /**
     * B站弹幕数（网关 _raw.danmaku 透传；其他平台/缓存回填时为 null）。
     * 加性字段（2026-09 B站质量 pass 新增）：与 playCount 取 OR 判定热度，
     * MV 类投稿常关闭弹幕，纯播放量会误伤，故双信号任一达标即放行。
     */
    private Long danmakuCount;
}
