package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchQueryHints 纯逻辑测试（无 Spring、无 Mockito）。
 */
@DisplayName("SearchQueryHints 查询提示测试")
class SearchQueryHintsTest {

    private static final List<String> HOTS = SongSearchService.hotKeywords();

    @Nested @DisplayName("归一化")
    class NormalizeTest {

        @Test @DisplayName("全角转半角、多空格压缩、去首尾空白")
        void fullWidthAndSpaces() {
            assertEquals("QQ 歌", SearchQueryHints.normalizeQuery("　ＱＱ  歌　"));
        }

        @Test @DisplayName("中文原样保留，null 返回空串")
        void chineseUntouchedAndNull() {
            assertEquals("晴天", SearchQueryHints.normalizeQuery("晴天"));
            assertEquals("", SearchQueryHints.normalizeQuery(null));
        }
    }

    @Nested @DisplayName("拼音别名")
    class AliasTest {

        @Test @DisplayName("全拼与首字母命中热词")
        void fullAndInitial() {
            assertEquals("周杰伦", SearchQueryHints.resolveAlias("zjl"));
            assertEquals("周杰伦", SearchQueryHints.resolveAlias("zhoujielun"));
            assertEquals("晴天", SearchQueryHints.resolveAlias("qt"));
            assertEquals("晴天", SearchQueryHints.resolveAlias("QINGTIAN"));
        }

        @Test @DisplayName("非别名与空白返回 null")
        void unknownAndBlank() {
            assertNull(SearchQueryHints.resolveAlias("晴天"));
            assertNull(SearchQueryHints.resolveAlias("xyz-nomatch"));
            assertNull(SearchQueryHints.resolveAlias("  "));
            assertNull(SearchQueryHints.resolveAlias(null));
        }
    }

    @Nested @DisplayName("拼写纠错")
    class TypoTest {

        @Test @DisplayName("一字之差纠正到热词")
        void oneCharOff() {
            assertEquals("晴天", SearchQueryHints.didYouMean("晴添", HOTS));
        }

        @Test @DisplayName("精确命中与远词不纠错")
        void exactAndFar() {
            assertNull(SearchQueryHints.didYouMean("晴天", HOTS));
            assertNull(SearchQueryHints.didYouMean("zzzqqq不存在", HOTS));
            assertNull(SearchQueryHints.didYouMean("晴", HOTS));
        }

        @Test @DisplayName("编辑距离 sanity")
        void editDistanceSanity() {
            assertEquals(0, SearchQueryHints.editDistance("晴天", "晴天"));
            assertEquals(1, SearchQueryHints.editDistance("晴添", "晴天"));
            assertEquals(3, SearchQueryHints.editDistance("kitten", "sitting"));
        }
    }

    @Nested @DisplayName("空结果回退建议")
    class SuggestTest {

        @Test @DisplayName("别名优先于纠错")
        void aliasFirst() {
            assertEquals("周杰伦", SearchQueryHints.suggestForEmpty("zjl", HOTS, false));
        }

        @Test @DisplayName("无别名时纠错补上")
        void typoFallback() {
            assertEquals("晴天", SearchQueryHints.suggestForEmpty("晴添", HOTS, false));
        }

        @Test @DisplayName("全角空白查询归一化为标准词")
        void normalizedVariant() {
            assertEquals("晴天", SearchQueryHints.suggestForEmpty("　晴天　", HOTS, false));
        }

        @Test @DisplayName("全上游失败与空白查询不给建议")
        void noSuggestWhenAllFailedOrBlank() {
            assertNull(SearchQueryHints.suggestForEmpty("晴添", HOTS, true));
            assertNull(SearchQueryHints.suggestForEmpty("   ", HOTS, false));
            assertNull(SearchQueryHints.suggestForEmpty(null, HOTS, false));
            assertNull(SearchQueryHints.suggestForEmpty("zzzqqq不存在", HOTS, false));
        }
    }
}
