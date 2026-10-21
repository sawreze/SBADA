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

        // 해제(두 번 해도 같음) → 관심목록 비어 있음
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/items/" + itemId + "/like").header("Authorization", fan.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liked").value(false));
        }
        mvc.perform(get("/api/me/likes").header("Authorization", fan.bearer()))
                .andExpect(jsonPath("$.items", hasSize(0)));

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
}
