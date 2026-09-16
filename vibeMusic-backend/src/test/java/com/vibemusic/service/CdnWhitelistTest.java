package com.vibemusic.service;

import com.vibemusic.common.utils.CdnWhitelist;
import com.vibemusic.config.NeteaseApiConfig;
import com.vibemusic.controller.StreamController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CDN 白名单单一来源回归测试（R4-B2）。
 * <p>
 * 生效值唯一来源 = application.yml 的 stream.cdn-whitelist 默认值；
 * 两处 @Value 均为无默认值引用式（缺失即启动失败），杜绝四份清单漂移。
 */
@DisplayName("CDN 白名单单一来源回归测试")
class CdnWhitelistTest {

    /** 从 classpath 的 application.yml 提取 cdn-whitelist 默认值并按逗号拆分为清单。 */
    static List<String> resolveWhitelistFromYml() throws Exception {
        try (var in = CdnWhitelistTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath 应能读到 application.yml");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(yml.split("\n"));
            StringBuilder block = new StringBuilder();
            boolean inBlock = false;
            for (String line : lines) {
                if (line.contains("cdn-whitelist:")) {
                    inBlock = true;
                    block.append(line.substring(line.indexOf("cdn-whitelist:")));
                } else if (inBlock) {
                    if (line.isBlank() || (!line.startsWith(" ") && !line.startsWith("\t"))) break;
                    block.append(line);
                }
            }
            String raw = block.toString();
            assertTrue(raw.contains("${STREAM_CDN_WHITELIST:"),
                    "白名单必须保留 STREAM_CDN_WHITELIST 环境覆盖入口，实际: " + raw);
            String inner = raw.substring(raw.indexOf("${STREAM_CDN_WHITELIST:") + "${STREAM_CDN_WHITELIST:".length());
            assertTrue(inner.contains("}"), "白名单占位符必须闭合，实际: " + raw);
            inner = inner.substring(0, inner.indexOf('}'));
            return Arrays.stream(inner.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    @Test
    @DisplayName("生效白名单覆盖全部五平台域名（含咪咕 *.migu.cn）")
    void whitelistCoversAllFivePlatforms() throws Exception {
        List<String> whitelist = resolveWhitelistFromYml();
        // 网易云
        assertTrue(whitelist.contains("*.music.126.net"), "缺网易云域，实际: " + whitelist);
        // QQ 音乐（含腾讯系 CDN）
        for (String d : new String[]{"*.gtimg.cn", "*.stream.qqmusic.qq.com", "*.tc.qq.com", "*.tencentmusic.com"}) {
            assertTrue(whitelist.contains(d), "缺QQ域 " + d + "，实际: " + whitelist);
        }
        // 咪咕（R4-B2 回归核心：缺失即播放/下载 403）
        assertTrue(whitelist.contains("*.migu.cn"), "缺咪咕域，实际: " + whitelist);
        // B站
        for (String d : new String[]{"*.bilivideo.com", "*.hdslb.com"}) {
            assertTrue(whitelist.contains(d), "缺B站域 " + d + "，实际: " + whitelist);
        }
        // 酷狗取链经网关直返、无独立 CDN 域；统一清单共 8 项，与 .env.example 注释一致
        assertEquals(8, whitelist.size(), "白名单应恰为统一后的 8 项，实际: " + whitelist);
    }

    @Test
    @DisplayName("两处 @Value 均为无默认值引用式，缺失即启动失败")
    void valueAnnotationsAreReferenceOnly() throws Exception {
        for (Class<?> clazz : new Class<?>[]{StreamController.class, NeteaseApiService.class}) {
            Field f = clazz.getDeclaredField("cdnWhitelistConfig");
            Value v = f.getAnnotation(Value.class);
            assertNotNull(v, clazz.getSimpleName() + " 应有 @Value 注解");
            assertEquals("${stream.cdn-whitelist}", v.value(),
                    clazz.getSimpleName() + " 不许带内联默认清单，实际: " + v.value());
            assertFalse(Objects.requireNonNull(v.value()).contains(":"),
                    clazz.getSimpleName() + " 内联默认会掩盖 yml 缺失，必须 fail-fast");
        }
    }

    @Test
    @DisplayName("yml 默认值与根目录 .env.example 注释清单一致（四方同源）")
    void ymlDefaultMatchesEnvExample() throws Exception {
        List<String> ymlList = resolveWhitelistFromYml().stream().sorted().toList();
        Path envExample = Path.of("../.env.example");
        assertTrue(Files.exists(envExample), "应能读到仓库根 .env.example，当前工作目录: " + Path.of(".").toAbsolutePath());
        String exampleValue = Files.readAllLines(envExample, StandardCharsets.UTF_8).stream()
                .map(l -> l.trim().replaceFirst("^#\\s*", ""))
                .filter(l -> l.startsWith("STREAM_CDN_WHITELIST="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(".env.example 缺 STREAM_CDN_WHITELIST 行"));
        List<String> exampleList = CdnWhitelist.parse(
                exampleValue.substring("STREAM_CDN_WHITELIST=".length())).stream().sorted().toList();
        assertEquals(ymlList, exampleList, "yml 默认值必须与 .env.example 注释清单逐项一致");
    }

    @Test
    @DisplayName("两处消费方对同一 host 矩阵判定一致（经共享匹配器）")
    void bothConsumersAgreeOnHostMatrix() throws Exception {
        List<String> whitelist = resolveWhitelistFromYml();
        String joined = String.join(",", whitelist);
        StreamController controller = new StreamController(null, null, null, null, RestClient.builder());
        ReflectionTestUtils.setField(controller, "cdnWhitelistConfig", joined);
        NeteaseApiService apiService = new NeteaseApiService(new NeteaseApiConfig(), null, RestClient.builder());
        ReflectionTestUtils.setField(apiService, "cdnWhitelistConfig", joined);
        String[] allow = {"p1.music.126.net", "music.126.net", "y.gtimg.cn",
                "dl.stream.qqmusic.qq.com", "tc.qq.com", "c.tencentmusic.com",
                "f.migu.cn", "upos-sz-mirrorcos.bilivideo.com", "i0.hdslb.com"};
        String[] deny = {"evil.example", "music.126.net.evil.com", "", null};
        for (String host : allow) {
            assertTrue((boolean) ReflectionTestUtils.invokeMethod(controller, "isCdnWhitelisted", host),
                    "StreamController 应放行 " + host);
            assertTrue((boolean) ReflectionTestUtils.invokeMethod(apiService, "isUrlAllowed", "https://" + host + "/x.mp3"),
                    "NeteaseApiService 应放行 " + host);
        }
        for (String host : deny) {
            assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "isCdnWhitelisted", host),
                    "StreamController 应拦截 " + host);
            Object url = host == null ? null : "https://" + host + "/x.mp3";
            assertFalse((boolean) ReflectionTestUtils.invokeMethod(apiService, "isUrlAllowed", url),
                    "NeteaseApiService 应拦截 " + host);
        }
    }
}
