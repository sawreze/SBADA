package com.sbada;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 내 정보 · 프로필 사진 · 동네 설정 PUT /api/me/region (찜은 2026-11-25 물품 서버 ItemLikeApiTest 로 옮김) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-like-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class LikeAndRegionApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    com.sbada.events.OutboxRepository outbox;

    @Test
    void changeProfilePasswordAndPhoto() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "prof");
        TestSupport.Account other = TestSupport.newAccount(mvc, "prof");
        String newName = "renamed_" + (System.nanoTime() % 100000);

        // 닉네임 · 아이디 변경(토큰은 그대로 유효)
        mvc.perform(put("/api/me/profile").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + newName + "\",\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value(newName))
                .andExpect(jsonPath("$.user.nickname").value("새닉네임"));
        // 물품 서버의 판매자 사본도 고치도록 허브(Kafka sbada.user.events)로 알릴 이벤트가 아웃박스에
        org.assertj.core.api.Assertions.assertThat(outbox.findAll()).anySatisfy(e -> org.assertj.core.api.Assertions
                .assertThat(e.getPayload()).contains("USER_UPDATED").contains("새닉네임").contains("\"userId\":" + a.id()));
        // 남이 쓰는 아이디 → 409
        mvc.perform(put("/api/me/profile").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + other.username() + "\",\"nickname\":\"새닉네임\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));

        // 비밀번호: 기존 비밀번호가 틀리면 400, 맞으면 204 → 새 비밀번호로 로그인
        mvc.perform(put("/api/me/password").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"new-pass-1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        mvc.perform(put("/api/me/password").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"study-1234\",\"newPassword\":\"new-pass-1234\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + newName + "\",\"password\":\"new-pass-1234\"}"))
                .andExpect(status().isOk());

        // 프로필 사진: 내가 올린 사진만, null 이면 지우기
        // 사진은 물품 서버에 있다 → 메인 서버는 배달부(gRPC GetImage)로 '내가 올린 사진인지' 확인(테스트는 가짜 물품 서비스)
        long mine = FakeServices.addImage(a.id());
        long theirs = FakeServices.addImage(other.id());
        mvc.perform(put("/api/me/photo").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":" + theirs + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/me/photo").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":" + mine + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.profileImageUrl").value("/api/images/" + mine));
        mvc.perform(put("/api/me/photo").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.profileImageUrl").doesNotExist());
    }

    @Test
    void changeRegion() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "region");
        mvc.perform(put("/api/me/region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\"  마포구 합정동 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.region").value("마포구 합정동"));
        mvc.perform(get("/api/me").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.user.region").value("마포구 합정동"));
        mvc.perform(put("/api/me/region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\"" + "가".repeat(21) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/region").contentType(MediaType.APPLICATION_JSON).content("{\"region\":\"서울\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** 내 동네 최대 2개: 추가 PUT /api/me/second-region · 바꾸기 POST /api/me/region/swap · 지우기 DELETE (V13) */
    @Test
    void secondRegionAddSwapDelete() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "region2");
        mvc.perform(put("/api/me/region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\"마포구 망원동\"}"))
                .andExpect(jsonPath("$.user.secondRegion").doesNotExist());

        // 추가한 동네가 없으면 바꾸기 409
        mvc.perform(post("/api/me/region/swap").header("Authorization", a.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SECOND_REGION"));
        // 지금 동네와 같은 동네는 추가 못 함 400, 빈 값 400, 로그인 없이 401
        mvc.perform(put("/api/me/second-region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\" 마포구 망원동 \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_REGION"));
        mvc.perform(put("/api/me/second-region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/second-region").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"region\":\"서울\"}"))
                .andExpect(status().isUnauthorized());

        // 추가 → GET /api/me 에도 보임
        mvc.perform(put("/api/me/second-region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\" 부산 해운대구 우동 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.region").value("마포구 망원동"))
                .andExpect(jsonPath("$.user.secondRegion").value("부산 해운대구 우동"));
        mvc.perform(get("/api/me").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.user.secondRegion").value("부산 해운대구 우동"));

        // 바꾸기: 두 동네가 서로 자리를 바꾼다. 두 번 바꾸면 처음으로
        mvc.perform(post("/api/me/region/swap").header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.region").value("부산 해운대구 우동"))
                .andExpect(jsonPath("$.user.secondRegion").value("마포구 망원동"));
        mvc.perform(post("/api/me/region/swap").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.user.region").value("마포구 망원동"))
                .andExpect(jsonPath("$.user.secondRegion").value("부산 해운대구 우동"));

        // 지금 동네를 두 번째 동네와 같게 바꾸면 두 번째는 비워진다(같은 동네가 두 번 남지 않게)
        mvc.perform(put("/api/me/region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\"부산 해운대구 우동\"}"))
                .andExpect(jsonPath("$.user.region").value("부산 해운대구 우동"))
                .andExpect(jsonPath("$.user.secondRegion").doesNotExist());

        // 다시 추가 → 지우기(두 번 해도 200)
        mvc.perform(put("/api/me/second-region").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"region\":\"마포구 합정동\"}"))
                .andExpect(jsonPath("$.user.secondRegion").value("마포구 합정동"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/me/second-region").header("Authorization", a.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.secondRegion").doesNotExist());
        }
    }
}
