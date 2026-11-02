package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/** 로그인 화면 '로그인 유지': rememberMe=true 면 30일 토큰, 없거나 false 면 2시간 토큰 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-remember-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class RememberLoginApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void rememberMeGivesLongLivedToken() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "remember");
        // 기본: 2시간
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"study-1234\"}".formatted(a.username())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(7200));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"study-1234\",\"rememberMe\":false}".formatted(a.username())))
                .andExpect(jsonPath("$.expiresIn").value(7200));
        // 로그인 유지: 30일, 그 토큰으로 보호 API 가 된다
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"study-1234\",\"rememberMe\":true}".formatted(a.username())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(2592000))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }
}
