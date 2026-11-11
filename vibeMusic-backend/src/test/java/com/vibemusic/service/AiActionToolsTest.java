package com.vibemusic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AiActionTools 的纯函数部分（歌词时间轴剥离）。
 * 网络类执行路径（收藏/歌词取数）依赖 Spring 装配，由集成层覆盖。
 */
@DisplayName("AI 收藏/歌词工具纯函数测试")
class AiActionToolsTest {

    @Test
    @DisplayName("剥 LRC 时间轴：保留正文、丢弃空行、保留多时间戳行的文本")
    void shouldStripLrcTimestamps() {
        String lrc = """
                [00:00.00] 作词 : 张三
                [00:01.50] 作曲 : 李四
                [00:12.30]第一句歌词
                [00:12.30][00:45.10]重复句
                [00:20.00]
                [00:30.00]最后一句
                """;
        String text = AiActionTools.stripLrcTags(lrc);
        assertThat(text).isEqualTo("作词 : 张三\n作曲 : 李四\n第一句歌词\n重复句\n最后一句");
    }

    @Test
    @DisplayName("纯文本歌词：原样保留")
    void shouldKeepPlainLyrics() {
        assertThat(AiActionTools.stripLrcTags("没有时间轴\n第二行")).isEqualTo("没有时间轴\n第二行");
    }

    @Test
    @DisplayName("全空输入返回空串")
    void shouldReturnEmptyForBlank() {
        assertThat(AiActionTools.stripLrcTags("")).isEmpty();
        assertThat(AiActionTools.stripLrcTags("[00:00.00]\n[00:01.00]")).isEmpty();
    }
}
