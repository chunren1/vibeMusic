package com.vibemusic.service;

import com.vibemusic.dto.SongDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 推荐后处理纯函数测试（v4：相邻同歌手打散）。
 *
 * <p>只测静态方法，不需要构造 personalizer（它的依赖是 mapper 与线程池）。</p>
 */
class RecommendPersonalizerTest {

    private static SongDTO song(String id, String artist) {
        SongDTO s = new SongDTO();
        s.setSourceId(id);
        s.setName("歌曲" + id);
        s.setArtist(artist);
        return s;
    }

    @Test
    @DisplayName("相邻同歌手：交换到不相邻")
    void breaksAdjacentSameArtist() {
        List<SongDTO> list = new ArrayList<>(List.of(
                song("1", "周杰伦"),
                song("2", "周杰伦"),
                song("3", "陈奕迅")));

        RecommendPersonalizer.avoidAdjacentSameArtist(list);

        assertNotEquals(list.get(0).getArtist(), list.get(1).getArtist(),
                "前两首不应仍是同一歌手");
        assertEquals(3, list.size(), "只交换顺序，不增删元素");
    }

    @Test
    @DisplayName("全部同一歌手：保持原样（无解，允许相邻）")
    void allSameArtistKeepsOrder() {
        List<SongDTO> list = new ArrayList<>(List.of(
                song("1", "周杰伦"),
                song("2", "周杰伦"),
                song("3", "周杰伦")));

        RecommendPersonalizer.avoidAdjacentSameArtist(list);

        assertEquals(3, list.size());
        assertEquals("周杰伦", list.get(0).getArtist());
    }

    @Test
    @DisplayName("歌手为空（不同曲目）：不当作同一歌手交换")
    void emptyArtistNotGrouped() {
        List<SongDTO> list = new ArrayList<>(List.of(
                song("1", ""),
                song("2", "周杰伦")));

        RecommendPersonalizer.avoidAdjacentSameArtist(list);

        assertEquals("", list.get(0).getArtist());
    }
}
