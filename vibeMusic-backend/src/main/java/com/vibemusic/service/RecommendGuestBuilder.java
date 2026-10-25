package com.vibemusic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.vibemusic.dto.RecommendResult;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.entity.PlayHistory;
import com.vibemusic.entity.Song;
import com.vibemusic.mapper.SongMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 游客推荐与随机兜底（从 RecommendService 抽出，行为不变）。
 *
 * <p>负责：游客随机 8 首、随机失败的空结果降级、个性化结果为空时的兜底、
 * 推荐理由/欢迎语文案、离线缓存状态标记。文案与数量上限原样保留。
 * <p>兜底永远是「尽力给歌」：随机源异常时退化为空列表 + 固定文案，不向上抛。
 */
@Slf4j
final class RecommendGuestBuilder {

    /** 基础随机歌曲数，保证多样性（个性化 ① 随机打底也用它）。 */
    static final int RANDOM_BASE = 4;

    private final SongSearchService songSearchService;
    private final SongMapper songMapper;
    private final StorageService storageService;

    RecommendGuestBuilder(SongSearchService songSearchService, SongMapper songMapper,
                          StorageService storageService) {
        this.songSearchService = songSearchService;
        this.songMapper = songMapper;
        this.storageService = storageService;
    }

    /**
     * 未登录游客推荐
     */
    RecommendResult buildGuest() {
        return buildGuestResult("登录后享受个性化推荐~");
    }

    RecommendResult buildGuestResult(String greeting) {
        try {
            List<SongDTO> songs = songSearchService.getRandomSongs(RecommendService.RECOMMEND_COUNT);
            markOfflineStatus(songs);
            return RecommendResult.builder()
                    .songs(songs)
                    .greeting(greeting)
                    .type("random")
                    .build();
        } catch (Exception e) {
            log.error("随机推荐失败", e);
            return RecommendResult.builder()
                    .songs(Collections.emptyList())
                    .greeting("推荐服务暂时不可用")
                    .type("random")
                    .build();
        }
    }

    /**
     * 生成推荐理由（基于播放历史的"温度"）
     */
    String buildReason(List<PlayHistory> history) {
        if (history.isEmpty()) return null;

        // 找播放次数最多的歌
        Map<String, Long> songCount = history.stream()
                .collect(Collectors.groupingBy(
                        h -> h.getSongName() != null ? h.getSongName() : "未知歌曲",
                        Collectors.counting()));
        String topSong = songCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        // 找最常听的歌手
        Map<String, Long> artistCount = history.stream()
                .filter(h -> h.getArtist() != null)
                .collect(Collectors.groupingBy(PlayHistory::getArtist, Collectors.counting()));
        String topArtist = artistCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        if (topSong != null && topArtist != null && songCount.get(topSong) >= 3) {
            return "因为你最近喜欢" + topArtist + "的《" + topSong + "》，为你推荐同风格歌曲";
        }
        if (topArtist != null) {
            return "基于你常听" + topArtist + "的歌曲，为你推荐";
        }
        return "根据你的播放历史，为你推荐";
    }

    /**
     * 生成动态欢迎语
     */
    String buildGreeting(int historyCount, List<String> topArtists) {
        if (historyCount == 0) return "最近没有听过歌？试试这些吧~";
        if (topArtists.isEmpty()) return "为你推荐一些好听的歌~";
        String artist = topArtists.get(0);
        if (historyCount < 5) return "最近在听 " + artist + "？试试这些~";
        if (historyCount < 20) return "根据你的口味，为你推荐~";
        return "你常听 " + artist + "，这些应该也会喜欢~";
    }

    /**
     * 标记歌曲离线缓存状态 — 通过 DB song 表批量查询（消除 N 次 MinIO statObject HTTP 调用）
     * <p>
     * 逻辑：song.url != null → 歌曲已下载到 MinIO
     */
    void markOfflineStatus(List<SongDTO> songs) {
        if (songs.isEmpty()) return;
        try {
            List<String> ids = songs.stream()
                    .map(SongDTO::getSourceId).filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (ids.isEmpty()) return;
            // 一次 DB 查询替代 N 次 MinIO HTTP statObject
            Set<String> cachedIds = songMapper.selectList(
                    new LambdaQueryWrapper<Song>()
                            .in(Song::getSourceId, ids)
                            .isNotNull(Song::getUrl)
                            .select(Song::getSourceId))
                    .stream().map(Song::getSourceId)
                    .collect(Collectors.toSet());
            for (SongDTO s : songs) {
                s.setCached(s.getSourceId() != null && cachedIds.contains(s.getSourceId()));
            }
        } catch (Exception e) {
            log.warn("批量查询缓存状态失败，回退到逐个检查 MinIO", e);
            // 降级兜底：仅异常时逐个查 MinIO
            for (SongDTO s : songs) {
                if (s.getSourceId() != null) {
                    try { s.setCached(storageService.exists("songs/" + s.getSourceId() + ".mp3")); }
                    catch (Exception ex) { s.setCached(false); }
                }
            }
        }
    }
}
