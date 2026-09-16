package com.vibemusic.service;

import com.vibemusic.TransactionalServiceTest;
import com.vibemusic.common.exception.BusinessException;
import com.vibemusic.entity.Playlist;
import com.vibemusic.entity.PlaylistSong;
import com.vibemusic.mapper.PlaylistMapper;
import com.vibemusic.mapper.PlaylistSongMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PlaylistService 歌单服务测试
 * <p>
 * 覆盖：创建歌单(含去重)、添加歌曲(含鉴权+去重)、删除歌曲、
 * 删除歌单、批量删除、导入歌单、获取歌曲列表。
 */
@DisplayName("PlaylistService 歌单服务测试")
class PlaylistServiceTest extends TransactionalServiceTest {

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistMapper playlistMapper;

    @Autowired
    private PlaylistSongMapper songMapper;

    private static final Long USER_ID = 1L;
    private static final String SONG_SOURCE_ID = "000rh0dE2TyUic";
    private static final String SONG_NAME = "晴天";
    private static final String ARTIST = "周杰伦";
    private static final String COVER_URL = "http://img.test/cover.jpg";

    @Nested
    @DisplayName("create 创建歌单")
    class CreateTest {

        @Test
        @DisplayName("首次创建歌单应成功")
        void shouldCreatePlaylist() {
            Map<String, Object> result = playlistService.create(USER_ID, "我的歌单", "测试描述", COVER_URL);

            assertNotNull(result.get("id"));
            assertEquals("我的歌单", result.get("name"));
            assertEquals("测试描述", result.get("description"));
            assertEquals(0L, result.get("songCount"));
            assertNull(result.get("duplicate"));
        }

        @Test
        @DisplayName("同名歌单重复创建应返回已存在歌单并标记 duplicate")
        void shouldReturnDuplicatePlaylist() {
            Map<String, Object> first = playlistService.create(USER_ID, "重复歌单", null, null);
            Map<String, Object> second = playlistService.create(USER_ID, "重复歌单", null, null);

            assertEquals(first.get("id"), second.get("id"));
            assertEquals(true, second.get("duplicate"));
        }

        @Test
        @DisplayName("不同用户可以创建同名歌单")
        void shouldAllowSameNameForDifferentUsers() {
            playlistService.create(1L, "共享名", null, null);
            assertDoesNotThrow(() -> playlistService.create(2L, "共享名", null, null));
        }
    }

    @Nested
    @DisplayName("addSong 添加歌曲")
    class AddSongTest {

        @Test
        @DisplayName("向自己的歌单添加歌曲应成功")
        void shouldAddSongToOwnPlaylist() {
            Long playlistId = createPlaylistAndGetId("添加测试歌单");
            boolean added = playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID,
                    SONG_NAME, ARTIST, COVER_URL, 240);

            assertTrue(added);
            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);
            assertEquals(1, songs.size());
            assertEquals(SONG_NAME, songs.get(0).get("songName"));
        }

        @Test
        @DisplayName("重复添加同一首歌应返回 false")
        void shouldReturnFalseForDuplicateSong() {
            Long playlistId = createPlaylistAndGetId("去重测试歌单");
            playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID, SONG_NAME, ARTIST, COVER_URL, 240);
            boolean addedAgain = playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID,
                    SONG_NAME, ARTIST, COVER_URL, 240);

            assertFalse(addedAgain);
            assertEquals(1, playlistService.getSongs(USER_ID, playlistId).size());
        }

        @Test
        @DisplayName("向不存在的歌单添加歌曲应抛 404")
        void shouldThrow404ForNonExistentPlaylist() {
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    playlistService.addSong(USER_ID, 99999L, SONG_SOURCE_ID, SONG_NAME, ARTIST, COVER_URL, 240));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("向他人歌单添加歌曲应抛 403")
        void shouldThrow403ForOtherUserPlaylist() {
            Long playlistId = createPlaylistAndGetId("他人歌单");
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    playlistService.addSong(999L, playlistId, SONG_SOURCE_ID, SONG_NAME, ARTIST, COVER_URL, 240));
            assertEquals(403, ex.getCode());
        }
    }

    @Nested
    @DisplayName("getSongs 获取歌单歌曲")
    class GetSongsTest {

        @Test
        @DisplayName("空歌单应返回空列表")
        void shouldReturnEmptyForEmptyPlaylist() {
            Long playlistId = createPlaylistAndGetId("空歌单");
            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);
            assertTrue(songs.isEmpty());
        }

        @Test
        @DisplayName("封面 URL 应自动升级为 HTTPS")
        void shouldUpgradeCoverUrlToHttps() {
            Long playlistId = createPlaylistAndGetId("HTTPS测试歌单");
            playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID, SONG_NAME, ARTIST,
                    "http://img.test/cover.jpg", 240);

            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);
            assertEquals("https://img.test/cover.jpg", songs.get(0).get("coverUrl"));
        }

        @Test
        @DisplayName("非所有者读取他人歌单应抛 403")
        void shouldThrow403ForNonOwner() {
            Long playlistId = createPlaylistAndGetId("他人歌单不可读");
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    playlistService.getSongs(999L, playlistId));
            assertEquals(403, ex.getCode());
        }

        @Test
        @DisplayName("读取不存在的歌单应抛 404")
        void shouldThrow404ForMissingPlaylist() {
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    playlistService.getSongs(USER_ID, 99999L));
            assertEquals(404, ex.getCode());
        }

        @Test
        @DisplayName("损坏行（sourceId为空）应被跳过，好行仍正常返回")
        void shouldSkipCorruptRowsAndReturnGoodOnes() {
            Long playlistId = createPlaylistAndGetId("坏行跳过歌单");
            playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID, SONG_NAME, ARTIST, COVER_URL, 240);
            songMapper.insert(PlaylistSong.builder()
                    .playlistId(playlistId).sourceId("")
                    .songName(null).artist(null).coverUrl(null).duration(null).platform(null)
                    .build());

            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);

            assertEquals(1, songs.size());
            assertEquals(SONG_SOURCE_ID, songs.get(0).get("sourceId"));
        }

        @Test
        @DisplayName("字段全空但sourceId有效行应被保留且空字段给默认值")
        void shouldNullGuardRowsWithMissingFields() {
            Long playlistId = createPlaylistAndGetId("空字段歌单");
            songMapper.insert(PlaylistSong.builder()
                    .playlistId(playlistId).sourceId("half_empty_1")
                    .songName(null).artist(null).coverUrl(null).duration(null).platform(null)
                    .build());

            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);

            assertEquals(1, songs.size());
            Map<String, Object> row = songs.get(0);
            assertEquals("", row.get("songName"));
            assertEquals("", row.get("name"));
            assertEquals("", row.get("artist"));
            assertEquals("", row.get("coverUrl"));
            assertEquals(0, row.get("duration"));
            assertEquals("netease", row.get("platform"));
        }
    }

    @Nested
    @DisplayName("platform 平台字段")
    class PlatformTest {

        @Test
        @DisplayName("addSong 指定 platform 后 getSongs 应返回 name+songName+platform")
        void shouldRoundTripPlatform() {
            Long playlistId = createPlaylistAndGetId("平台测试歌单");
            boolean added = playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID,
                    SONG_NAME, ARTIST, COVER_URL, 240, "qq");

            assertTrue(added);
            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);
            assertEquals(1, songs.size());
            Map<String, Object> song = songs.get(0);
            assertEquals(SONG_NAME, song.get("name"));
            assertEquals(SONG_NAME, song.get("songName"));
            assertEquals("qq", song.get("platform"));
        }

        @Test
        @DisplayName("platform 为空时应默认 netease")
        void shouldDefaultBlankPlatformToNetease() {
            Long playlistId = createPlaylistAndGetId("平台默认歌单");
            playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID,
                    SONG_NAME, ARTIST, COVER_URL, 240, null);

            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);
            assertEquals("netease", songs.get(0).get("platform"));
            assertEquals(SONG_NAME, songs.get(0).get("name"));
        }

        @Test
        @DisplayName("老式 7 参 addSong 应默认 netease 并返回 name 字段")
        void shouldDefaultLegacyAddSongToNetease() {
            Long playlistId = createPlaylistAndGetId("兼容测试歌单");
            playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID,
                    SONG_NAME, ARTIST, COVER_URL, 240);

            List<Map<String, Object>> songs = playlistService.getSongs(USER_ID, playlistId);
            assertEquals("netease", songs.get(0).get("platform"));
            assertEquals(SONG_NAME, songs.get(0).get("name"));
            assertEquals(SONG_NAME, songs.get(0).get("songName"));
        }
    }

    @Nested
    @DisplayName("removeSong 删除歌单歌曲")
    class RemoveSongTest {

        @Test
        @DisplayName("删除自己歌单中的歌曲应成功")
        void shouldRemoveSongFromOwnPlaylist() {
            Long playlistId = createPlaylistAndGetId("删除测试歌单");
            playlistService.addSong(USER_ID, playlistId, SONG_SOURCE_ID, SONG_NAME, ARTIST, COVER_URL, 240);
            assertEquals(1, playlistService.getSongs(USER_ID, playlistId).size());

            playlistService.removeSong(USER_ID, playlistId, SONG_SOURCE_ID);
            assertTrue(playlistService.getSongs(USER_ID, playlistId).isEmpty());
        }

        @Test
        @DisplayName("删除不存在的歌单歌曲应抛 404")
        void shouldThrow404WhenRemovingFromNonExistentPlaylist() {
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    playlistService.removeSong(USER_ID, 99999L, SONG_SOURCE_ID));
            assertEquals(404, ex.getCode());
        }
    }

    @Nested
    @DisplayName("delete 删除歌单")
    class DeleteTest {

        @Test
        @DisplayName("删除自己的歌单应成功")
        void shouldDeleteOwnPlaylist() {
            Long playlistId = createPlaylistAndGetId("待删除歌单");
            playlistService.delete(USER_ID, playlistId);

            List<Map<String, Object>> playlists = playlistService.listPlaylists(USER_ID);
            assertTrue(playlists.stream().noneMatch(p -> playlistId.equals(p.get("id"))));
        }

        @Test
        @DisplayName("删除他人歌单应抛 403")
        void shouldThrow403WhenDeletingOtherUserPlaylist() {
            Long playlistId = createPlaylistAndGetId("他人歌单");
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    playlistService.delete(999L, playlistId));
            assertEquals(403, ex.getCode());
        }
    }

    @Nested
    @DisplayName("deleteBatch 批量删除歌单")
    class DeleteBatchTest {

        @Test
        @DisplayName("批量删除自己的歌单应返回删除数量")
        void shouldDeleteMultipleOwnPlaylists() {
            Long pl1 = createPlaylistAndGetId("批量删除1");
            Long pl2 = createPlaylistAndGetId("批量删除2");
            Long pl3 = createPlaylistAndGetId("批量删除3");

            int deleted = playlistService.deleteBatch(USER_ID, List.of(pl1, pl2, pl3));
            assertEquals(3, deleted);
        }

        @Test
        @DisplayName("批量删除含他人歌单时只删除自己的")
        void shouldOnlyDeleteOwnPlaylistsInBatch() {
            Long myPlaylist = createPlaylistAndGetId("我的歌单");
            Long otherPlaylist = createPlaylistAndGetId("他人歌单");

            int deleted = playlistService.deleteBatch(999L, List.of(myPlaylist, otherPlaylist));
            assertEquals(0, deleted);
        }
    }

    @Nested
    @DisplayName("importPlaylist 导入外部歌单")
    class ImportPlaylistTest {

        @Test
        @DisplayName("导入新歌单应创建歌单并添加歌曲")
        void shouldImportNewPlaylist() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "song1", "name", "晴天", "artist", "周杰伦", "coverUrl", "http://img.test/1.jpg", "duration", 240),
                    Map.of("id", "song2", "name", "七里香", "artist", "周杰伦", "coverUrl", "http://img.test/2.jpg", "duration", 300)
            );

            int added = playlistService.importPlaylist(USER_ID, "导入歌单", COVER_URL, songs);
            assertEquals(2, added);
        }

        @Test
        @DisplayName("导入到已存在的同名歌单应追加歌曲")
        void shouldAppendToExistingPlaylist() {
            playlistService.create(USER_ID, "重复导入歌单", null, null);
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "new_song", "name", "新歌", "artist", "新歌手", "coverUrl", "", "duration", 180)
            );

            int added = playlistService.importPlaylist(USER_ID, "重复导入歌单", null, songs);
            assertEquals(1, added);
        }

        @Test
        @DisplayName("导入 source=qq 时所有歌曲应持久化 platform=qq")
        void shouldPersistQqPlatformOnImport() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "qq_song1", "name", "QQ歌1", "artist", "QQ歌手", "coverUrl", "", "duration", 200),
                    Map.of("id", "qq_song2", "name", "QQ歌2", "artist", "QQ歌手", "coverUrl", "", "duration", 210)
            );

            int added = playlistService.importPlaylist(USER_ID, "QQ导入歌单", null, songs, "qq");
            assertEquals(2, added);

            Long playlistId = findPlaylistIdByName("QQ导入歌单");
            List<Map<String, Object>> stored = playlistService.getSongs(USER_ID, playlistId);
            assertEquals(2, stored.size());
            assertTrue(stored.stream().allMatch(s -> "qq".equals(s.get("platform"))));
        }

        @Test
        @DisplayName("导入不传 source 时应默认 netease（向后兼容）")
        void shouldDefaultImportPlatformToNetease() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "legacy_song1", "name", "老歌1", "artist", "歌手", "coverUrl", "", "duration", 180)
            );

            int added = playlistService.importPlaylist(USER_ID, "默认平台导入歌单", null, songs);
            assertEquals(1, added);

            Long playlistId = findPlaylistIdByName("默认平台导入歌单");
            List<Map<String, Object>> stored = playlistService.getSongs(USER_ID, playlistId);
            assertEquals(1, stored.size());
            assertEquals("netease", stored.get(0).get("platform"));
        }

        @Test
        @DisplayName("导入 source=kugou 时所有歌曲应持久化 platform=kugou")
        void shouldPersistKugouPlatformOnImport() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "kg_hash1", "name", "酷狗歌1", "artist", "酷狗歌手", "coverUrl", "", "duration", 200)
            );

            int added = playlistService.importPlaylist(USER_ID, "酷狗导入歌单", null, songs, "kugou");
            assertEquals(1, added);

            Long playlistId = findPlaylistIdByName("酷狗导入歌单");
            List<Map<String, Object>> stored = playlistService.getSongs(USER_ID, playlistId);
            assertEquals(1, stored.size());
            assertEquals("kugou", stored.get(0).get("platform"));
        }

        @Test
        @DisplayName("导入 source 大小写/首尾空格应归一化为 kugou")
        void shouldNormalizeKugouPlatformCaseInsensitive() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "kg_hash2", "name", "酷狗歌2", "artist", "歌手", "coverUrl", "", "duration", 180)
            );

            int added = playlistService.importPlaylist(USER_ID, "酷狗归一歌单", null, songs, "KUGOU ");
            assertEquals(1, added);

            Long playlistId = findPlaylistIdByName("酷狗归一歌单");
            List<Map<String, Object>> stored = playlistService.getSongs(USER_ID, playlistId);
            assertEquals("kugou", stored.get(0).get("platform"));
        }

        @Test
        @DisplayName("导入 source=bilibili 时所有歌曲应持久化 platform=bilibili")
        void shouldPersistBilibiliPlatformOnImport() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "BV1De411p77r", "name", "B站歌1", "artist", "UP主", "coverUrl", "", "duration", 200)
            );

            int added = playlistService.importPlaylist(USER_ID, "B站导入歌单", null, songs, "BILIBILI ");
            assertEquals(1, added);

            Long playlistId = findPlaylistIdByName("B站导入歌单");
            List<Map<String, Object>> stored = playlistService.getSongs(USER_ID, playlistId);
            assertEquals(1, stored.size());
            assertEquals("bilibili", stored.get(0).get("platform"));
        }

        @Test
        @DisplayName("导入未知 source 时应回落 netease")
        void shouldFallbackUnknownSourceToNetease() {
            List<Map<String, Object>> songs = List.of(
                    Map.of("id", "unknown_song1", "name", "未知源歌", "artist", "歌手", "coverUrl", "", "duration", 180)
            );

            int added = playlistService.importPlaylist(USER_ID, "未知源导入歌单", null, songs, "kugou2");
            assertEquals(1, added);

            Long playlistId = findPlaylistIdByName("未知源导入歌单");
            List<Map<String, Object>> stored = playlistService.getSongs(USER_ID, playlistId);
            assertEquals("netease", stored.get(0).get("platform"));
        }
    }

    @Nested
    @DisplayName("seedDefaults 创建默认歌单")
    class SeedDefaultsTest {

        @Test
        @DisplayName("应为新用户创建 6 个默认歌单")
        void shouldCreateSixDefaultPlaylists() {
            playlistService.seedDefaults(USER_ID);

            List<Map<String, Object>> playlists = playlistService.listPlaylists(USER_ID);
            assertEquals(6, playlists.size());
        }
    }

    /** 辅助方法：创建歌单并返回 ID */
    private Long createPlaylistAndGetId(String name) {
        Map<String, Object> result = playlistService.create(USER_ID, name, null, null);
        return ((Number) result.get("id")).longValue();
    }

    /** 辅助方法：按名称查找歌单 ID（直查实体，绕开 H2/Map 键大小写差异） */
    private Long findPlaylistIdByName(String name) {
        Playlist pl = playlistMapper.selectOne(new LambdaQueryWrapper<Playlist>()
                .eq(Playlist::getUserId, USER_ID)
                .eq(Playlist::getName, name));
        if (pl == null) throw new AssertionError("歌单不存在: " + name);
        return pl.getId();
    }
}
