package com.sbada;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/** 찜(관심목록) POST/DELETE /api/items/{id}/like · GET /api/me/likes — 2026-11-25 메인 서버 LikeAndRegionApiTest 에서 옮김 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-item-like-test;DB_CLOSE_DELAY=-1",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key="
})
@AutoConfigureMockMvc
class ItemLikeApiTest {

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

}
