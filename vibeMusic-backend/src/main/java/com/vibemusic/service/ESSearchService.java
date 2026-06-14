package com.vibemusic.service;

import com.vibemusic.doc.SearchResultDoc;
import com.vibemusic.dto.SongDTO;
import com.vibemusic.repository.SearchResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESSearchService {

    private final SearchResultRepository repository;

    /** 批量插入搜索结果到 ES */
    public void indexSearchResults(String keyword, List<SongDTO> songs) {
        if (keyword == null || keyword.isEmpty() || songs == null || songs.isEmpty()) return;
        try {
            List<SearchResultDoc> docs = songs.stream().map(s -> toDoc(keyword, s)).toList();
            repository.saveAll(docs);
            log.debug("ES 索引搜索结果: keyword='{}' count={}", keyword, docs.size());
        } catch (Exception e) {
            log.warn("ES 索引失败（服务未就绪）: {}", e.getMessage());
        }
    }

    /** 从 ES 搜索缓存的歌曲 */
    public List<SongDTO> searchCached(String keyword) {
        try {
            List<SearchResultDoc> docs = repository.findByKeyword(keyword);
            if (docs.isEmpty()) return List.of();
            log.info("ES 缓存命中: '{}' count={}", keyword, docs.size());
            return docs.stream().map(this::toDTO).toList();
        } catch (Exception e) {
            log.warn("ES 查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ===== 转换 =====

    private SearchResultDoc toDoc(String keyword, SongDTO song) {
        return SearchResultDoc.builder()
                .id(md5(keyword + song.getSourceId()))
                .keyword(keyword).songId(song.getSourceId())
                .name(song.getName()).artist(song.getArtist()).album(song.getAlbum())
                .coverUrl(song.getCoverUrl()).duration(song.getDuration())
                .source(song.getPlatform()).finalScore(song.getFinalScore())
                .createTime(new Date())
                .build();
    }

    private SongDTO toDTO(SearchResultDoc doc) {
        SongDTO dto = new SongDTO();
        dto.setSourceId(doc.getSongId());
        dto.setName(doc.getName()); dto.setArtist(doc.getArtist()); dto.setAlbum(doc.getAlbum());
        dto.setCoverUrl(doc.getCoverUrl()); dto.setDuration(doc.getDuration());
        dto.setPlatform(doc.getSource()); dto.setFinalScore(doc.getFinalScore());
        return dto;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return Integer.toHexString(input.hashCode()); }
    }
}
