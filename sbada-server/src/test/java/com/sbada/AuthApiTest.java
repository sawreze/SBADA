package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.sbada.auth.JwtService;

/** 7장 P04 검사 항목: 가입 201·중복 409·로그인 200·틀린 비밀번호 401·토큰 없음/변조/만료 401·공개 GET 200 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class AuthApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtService jwt;

    @Test
    void signupLoginAndDuplicate() throws Exception {
        String body = "{\"username\":\"auth_user1\",\"password\":\"study-1234\",\"nickname\":\"가입자\"}";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("auth_user1"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_user1\",\"password\":\"study-1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.onboarded").value(false));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_user1\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
    }

    @Test
    void invalidSignupInputIs400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"AB\",\"password\":\"123\",\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void tokenRules() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "tok");
        // 공개 GET: 토큰 없이 200
        mvc.perform(get("/api/items")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        // 보호 API: 토큰 없음 401
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        // 정상 토큰 200
        mvc.perform(get("/api/me").header("Authorization", a.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(a.id()));
        // 변조 토큰 401 (공개 GET 이라도 헤더가 붙어 있으면 거절)
        String tampered = a.token().substring(0, a.token().length() - 3) + "abc";
        mvc.perform(get("/api/items").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        // 만료 토큰 401 TOKEN_EXPIRED
        String expired = jwt.issue(a.id(), Duration.ofSeconds(-10));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void onboardingSavesPreferences() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "onb");
        mvc.perform(put("/api/me/preferences").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wantedCategories\":[\"DIGITAL\",\"HOBBY\"],\"keywords\":[\"닌텐도\"],"
                                + "\"priceMin\":10000,\"priceMax\":300000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.onboarded").value(true))
                .andExpect(jsonPath("$.preferences.length()").value(2))
                .andExpect(jsonPath("$.preferences[0].weight").value(1.0))
                .andExpect(jsonPath("$.keywords[0]").value("닌텐도"));
        mvc.perform(put("/api/me/preferences").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wantedCategories\":[\"NOT_A_CATEGORY\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CATEGORY"));
    }
}
