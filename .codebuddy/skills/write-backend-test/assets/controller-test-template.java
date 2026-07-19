package com.vibemusic.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class XxxControllerTest extends BaseTest {

    private MockMvc mockMvc;

    @Override
    protected void setUpInternal() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Nested
    @DisplayName("GET /api/xxx")
    class GetXxx {

        @Test
        @DisplayName("200 - success")
        void shouldReturn200() throws Exception {
            mockMvc.perform(get("/api/xxx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("400 - missing params")
        void shouldReturn400() throws Exception {
            mockMvc.perform(get("/api/xxx"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("404 - not found")
        void shouldReturn404() throws Exception {
            mockMvc.perform(get("/api/xxx?id=99999"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/xxx")
    class PostXxx {

        @Test
        @DisplayName("200 - create success")
        void shouldReturn200() throws Exception {
            mockMvc.perform(post("/api/xxx")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("401 - not logged in")
        void shouldReturn401() throws Exception {
            mockMvc.perform(post("/api/xxx")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("400 - bad JSON body")
        void shouldReturn400OnBadBody() throws Exception {
            mockMvc.perform(post("/api/xxx")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{bad"))
                .andExpect(status().isBadRequest());
        }
    }
}
