package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.entity.Song;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SongService 单元测试")
class SongServiceTest extends TransactionalServiceTest {

    @Autowired
    private SongService songService;

    @Test
    @DisplayName("保存新歌 → insert 成功")
    void shouldInsertNewSong() {
        Song song = songService.saveDownloadedSong(
                "newsong001", "新歌", "歌手A", "专辑A",
                "http://img.test/cover.jpg", 240, "http://rustfs/songs/newsong001.mp3");
        assertThat(song).isNotNull();
        assertThat(song.getId()).isNotNull();
        assertThat(song.getSourceId()).isEqualTo("newsong001");
        assertThat(song.getUrl()).isEqualTo("http://rustfs/songs/newsong001.mp3");
    }

    @Test
    @Disabled("需要 MySQL ON DUPLICATE KEY UPDATE 语法，H2 不支持 — CI 用 Testcontainers 或 MariaDB4j 替代")
    @DisplayName("更新已有歌曲 → URL 覆盖")
    void shouldUpdateExistingSong() {
        // 第一次插入
        songService.saveDownloadedSong("update001", "旧歌", "歌手", null, null, null, "http://old.url");
        // 第二次更新
        Song updated = songService.saveDownloadedSong("update001", "新歌名", "歌手", null, null, null, "http://new.url");
        assertThat(updated.getUrl()).isEqualTo("http://new.url");
    }

    @Test
    @DisplayName("getBySourceId → 找到歌曲")
    void shouldFindBySourceId() {
        Song song = songService.getBySourceId("000rh0dE2TyUic");
        assertThat(song).isNotNull();
        assertThat(song.getName()).isEqualTo("晴天");
        assertThat(song.getArtist()).isEqualTo("周杰伦");
    }

    @Test
    @DisplayName("getBySourceId → 不存在返回 null")
    void shouldReturnNullWhenNotFound() {
        Song song = songService.getBySourceId("nonexistent");
        assertThat(song).isNull();
    }

    @Test
    @DisplayName("getById → 正常查询")
    void shouldFindById() {
        Song song = songService.getById(1L);
        assertThat(song).isNotNull();
        assertThat(song.getSourceId()).isEqualTo("000rh0dE2TyUic");
    }
}
