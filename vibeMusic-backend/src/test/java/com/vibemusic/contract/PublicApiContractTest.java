package com.vibemusic.contract;

import com.vibemusic.BaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开 API 信封契约（App / Web 消费方）。
 *
 * <p>只锁「结构」不锁「内容」：上游在测试环境不可达，list 允许为空；
 * 但 code/message/data 三层、分页字段类型、错误信封必须稳定。
 * 消费方（Android Kotlin / Vue）按这些 key 解析，字段改名会在这里先红。</p>
 */
@DisplayName("公开 API 信封契约")
class PublicApiContractTest extends BaseTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @Test
    @DisplayName("GET /api/songs/search：code/message/data{total,page,size,hasMore,list}")
    void searchEnvelope() throws Exception {
        mockMvc.perform(get("/api/songs/search")
                        .param("keyword", "晴天")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").isNumber())
                .andExpect(jsonPath("$.data.size").isNumber())
                .andExpect(jsonPath("$.data.hasMore").isBoolean())
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    @DisplayName("GET /api/songs/search 缺 keyword：400 + code/message 信封")
    void searchErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/songs/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isString());
    }
}
