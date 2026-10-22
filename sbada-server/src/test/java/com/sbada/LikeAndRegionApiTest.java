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

/** 찜(관심목록) POST/DELETE /api/items/{id}/like · GET /api/me/likes, 내 동네 설정 PUT /api/me/region */
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

    @Test
    void likeUnlikeAndList() throws Exception {
        TestSupport.Account owner = TestSupport.newAccount(mvc, "likeown");
        TestSupport.Account fan = TestSupport.newAccount(mvc, "likefan");
        long itemId = TestSupport.createItem(mvc, owner, "찜 테스트 헤드폰", "DIGITAL", "GOOD", "[]");

        // 로그인 없이 찜 → 401
        mvc.perform(post("/api/items/" + itemId + "/like")).andExpect(status().isUnauthorized());
        // 내 물건 찜 → 400 SELF_LIKE
        mvc.perform(post("/api/items/" + itemId + "/like").header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_LIKE"));
        // 없는 물건 → 404
        mvc.perform(post("/api/items/999999/like").header("Authorization", fan.bearer()))
                .andExpect(status().isNotFound());

        // 찜하기 두 번 → 결과 같음, 관심목록에는 한 번만
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/items/" + itemId + "/like").header("Authorization", fan.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liked").value(true));
        }
        mvc.perform(get("/api/me/likes").header("Authorization", fan.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(itemId))
                .andExpect(jsonPath("$.items[0].title").value("찜 테스트 헤드폰"));
        // 다른 사람의 관심목록에는 없다
        mvc.perform(get("/api/me/likes").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.items", hasSize(0)));
        // 찜 수(likeCount): 두 번 눌러도 1 — 물건 상세 · 관심목록 모두
        mvc.perform(get("/api/items/" + itemId).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.likeCount").value(1));
        mvc.perform(get("/api/me/likes").header("Authorization", fan.bearer()))
                .andExpect(jsonPath("$.items[0].likeCount").value(1));

        // 해제(두 번 해도 같음) → 관심목록 비어 있음
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/items/" + itemId + "/like").header("Authorization", fan.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liked").value(false));
        }
        mvc.perform(get("/api/me/likes").header("Authorization", fan.bearer()))
                .andExpect(jsonPath("$.items", hasSize(0)));
        mvc.perform(get("/api/items/" + itemId).header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.likeCount").value(0));

        // 내린 물건은 관심목록에서 빠진다
        mvc.perform(post("/api/items/" + itemId + "/like").header("Authorization", fan.bearer()))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/items/" + itemId).header("Authorization", owner.bearer()))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/me/likes").header("Authorization", fan.bearer()))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

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
        long mine = TestSupport.uploadImage(mvc, a);
        long theirs = TestSupport.uploadImage(mvc, other);
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
