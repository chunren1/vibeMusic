package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 网关透传结果 → {@link SongDTO} 的纯解析（从 SongSearchService 抽出，行为不变）。
 *
 * <p>无 I/O、无状态：上游字段缺失/脏数据一律宽容处理，永不抛异常给调用方
 * （解析失败返回 null / 空列表 / 原值保留）。
 */
@Slf4j
final class SongDtoParser {

    private SongDtoParser() {
    }

    /** 单条网关结果转 DTO；缺 id 或解析异常返回 null（调用方 filter 掉）。 */
    static SongDTO parsePlatformSong(Map<String, Object> raw) {
        try {
            String sourceId = raw.get("id") != null ? String.valueOf(raw.get("id")) : "";
            if (sourceId.isEmpty()) return null;
            String name = raw.get("name") != null ? String.valueOf(raw.get("name")) : "";
            String artists = raw.get("artists") != null ? String.valueOf(raw.get("artists")) : "未知歌手";
            String album = raw.get("album") != null ? String.valueOf(raw.get("album")) : "";
            String coverUrl = raw.get("cover") != null ? String.valueOf(raw.get("cover")) : "";
            int duration = 0;
            Object durObj = raw.get("duration");
            if (durObj instanceof Number) duration = ((Number) durObj).intValue() / 1000;
            Object vipObj = raw.get("vip");
            // 上游未知保持 null（中性、不得分），只有明确非付费才加权，见 applyNonVipBonus
            Boolean vip = vipObj instanceof Boolean ? (Boolean) vipObj : null;
            // B 站热度透传（网关 _raw.play/_raw.danmaku，加性；缺失即 null=未知，门控按 0 计）
            Long playCount = null;
            Long danmakuCount = null;
            Object rawObj = raw.get("_raw");
            if (rawObj instanceof Map<?, ?> rawMap) {
                playCount = toLongOrNull(rawMap.get("play"));
                if (playCount == null) playCount = toLongOrNull(rawMap.get("playCount"));
                danmakuCount = toLongOrNull(rawMap.get("danmaku"));
            }

            SongDTO dto = new SongDTO();
            dto.setSourceId(sourceId); dto.setName(name); dto.setArtist(artists);
            dto.setAlbum(album); dto.setCoverUrl(coverUrl); dto.setDuration(duration);
            dto.setVip(vip);
            dto.setAlias(parseAlias(raw.get("alias")));
            dto.setPlayCount(playCount); dto.setDanmakuCount(danmakuCount);
            return dto;
        } catch (Exception e) {
            log.warn("Failed to parse platform song: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把 http 封面改写为后端 image-proxy；B 站 CDN 封面（防盗链）同样改走后端代取。
     */
    static void rewriteHttpCoverUrl(SongDTO song) {
        if (song.getCoverUrl() != null && song.getCoverUrl().startsWith("http://")) {
            String encoded = java.net.URLEncoder.encode(song.getCoverUrl(), java.nio.charset.StandardCharsets.UTF_8);
            song.setCoverUrl("/api/image-proxy?url=" + encoded);
            return;
        }
        // B 站封面防盗链代取(2026-09 质量 pass 实测)：hdslb CDN 对外来 Referer 回 403
        // (curl 验证：无 Referer→200，外站 Referer→403，bilibili Referer→200)，
        // 浏览器从 App 域名直连必带外站 Referer → 裂图。网关 pic 透传本身无误，
        // App 亦无平台特判(纯 coverUrl 渲染)，故在此统一改走后端 image-proxy
        // (ProxyController 白名单已含 i0/i1/i2.hdslb.com，服务端无 Referer 拉取→200)。
        // 仅代理白名单内三域名，其他 https 原样保留；已代理的不重复包。
        if (song.getCoverUrl() != null && isBiliCdnCover(song.getCoverUrl())
                && !song.getCoverUrl().startsWith("/api/image-proxy")) {
            String encoded = java.net.URLEncoder.encode(song.getCoverUrl(), java.nio.charset.StandardCharsets.UTF_8);
            song.setCoverUrl("/api/image-proxy?url=" + encoded);
        }
    }

    /** B 站封面 CDN 域名判定：与 ProxyController ALLOWED_HOSTS 内三域名镜像，防发出不可代理 URL。 */
    static boolean isBiliCdnCover(String coverUrl) {
        if (coverUrl == null) return false;
        try {
            String host = java.net.URI.create(coverUrl).getHost();
            return com.vibemusic.common.utils.BiliCdnHosts.isCoverHost(host);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析网关透传的译名/别名（`alias` 数组或单字符串）。
     * 上游缺失、空数组、非字符串条目一律忽略；永不抛异常（老数据/脏数据视为无别名）。
     */
    static List<String> parseAlias(Object raw) {
        if (raw instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? Collections.emptyList() : List.of(t);
        }
        if (!(raw instanceof List<?> list)) return Collections.emptyList();
        List<String> alias = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o == null) continue;
            String t = String.valueOf(o).trim();
            if (!t.isEmpty() && !alias.contains(t)) alias.add(t);
        }
        return alias;
    }

    /** 网关 _raw 热度字段宽容转 Long：Number 直接取，数字字符串解析，其他一律 null。 */
    static Long toLongOrNull(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && s.trim().matches("-?\\d+")) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
