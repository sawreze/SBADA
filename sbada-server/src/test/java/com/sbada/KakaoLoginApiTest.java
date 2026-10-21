package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.sbada.auth.KakaoUserClient;
import com.sbada.common.ApiException;

/**
 * 카카오 로그인 API: 진짜 카카오 서버 대신 가짜 KakaoUserClient(@Primary)로 바꿔 끼워 검사한다.
 *  - 처음 로그인하면 계정이 생기고(onboarded=false), 두 번째에는 같은 계정으로 들어온다.
 *  - 카카오가 거절한 토큰은 401, 빈 토큰은 400.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-kakao-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class KakaoLoginApiTest {

    /** 토큰 → 카카오 회원 (가짜 카카오 서버) */
    static final Map<String, KakaoUserClient.KakaoProfile> KAKAO = Map.of(
            "token-haein", new KakaoUserClient.KakaoProfile(4_100_000_001L, "해인"),
            "token-noname", new KakaoUserClient.KakaoProfile(4_100_000_002L, null));

    @TestConfiguration
    static class FakeKakao {
        @Bean
        @Primary
        KakaoUserClient fakeKakaoUserClient() {
            return token -> {
                KakaoUserClient.KakaoProfile p = KAKAO.get(token);
                if (p == null) {
                    throw new ApiException(401, "KAKAO_TOKEN_INVALID", "카카오 로그인이 만료되었거나 올바르지 않습니다.");
                }
                return p;
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void firstKakaoLoginCreatesAccountAndSecondReusesIt() throws Exception {
        String first = kakao("token-haein")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value("kakao_4100000001"))
                .andExpect(jsonPath("$.user.nickname").value("해인"))
                .andExpect(jsonPath("$.user.onboarded").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Integer id = JsonPath.read(first, "$.user.id");

        // 받은 스바다 JWT 로 보호 API 사용 가능
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + JsonPath.read(first, "$.accessToken")))
                .andExpect(status().isOk());

        // 같은 카카오 회원이 다시 로그인 → 같은 계정
        kakao("token-haein").andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(id));
    }

    @Test
    void nicknameFallsBackWhenKakaoHasNone() throws Exception {
        kakao("token-noname").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.nickname").value("카카오0002"));
    }

    @Test
    void rejectedOrMissingTokens() throws Exception {
        kakao("forged-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("KAKAO_TOKEN_INVALID"));
        mvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON).content("{\"accessToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions kakao(String token) throws Exception {
        return mvc.perform(post("/api/auth/kakao").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessToken\":\"" + token + "\"}"));
    }
}
