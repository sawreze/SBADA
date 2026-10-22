package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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

import com.sbada.ai.ItemSummaryTestAccess;

/** AI 상품 요약 GET /api/items/{id}/summary (V15) · 기기 토큰 등록/지우기 POST /api/devices(/remove) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-summary-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class ItemSummaryApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void summaryNeedsLoginAndFallsBackWithoutAi() throws Exception {
        TestSupport.Account owner = TestSupport.newAccount(mvc, "sumown");
        TestSupport.Account viewer = TestSupport.newAccount(mvc, "sumview");
        long itemId = TestSupport.createItem(mvc, owner, "요약 테스트 블루투스 스피커", "DIGITAL", "GOOD", "[\"BOOK\"]");

        mvc.perform(get("/api/items/" + itemId + "/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/items/999999/summary").header("Authorization", viewer.bearer()))
                .andExpect(status().isNotFound());
        // AI 가 없으면(fallback) 글 정보로 만든 기본 요약 — 저장하지 않으므로 cached=false
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/api/items/" + itemId + "/summary").header("Authorization", viewer.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.itemId").value(itemId))
                    .andExpect(jsonPath("$.provider").value("fallback"))
                    .andExpect(jsonPath("$.cached").value(false))
                    .andExpect(jsonPath("$.summary", containsString("요약 테스트 블루투스 스피커")))
                    .andExpect(jsonPath("$.summary", containsString("사용감 적음")));
        }
    }

    @Test
    void parseAndCleanGemmaAnswer() throws Exception {
        assertThat(ItemSummaryTestAccess.parse("```json\n{\"summary\": \"  작은 스피커예요.\\n소리가 맑아요. \"}\n```"))
                .isEqualTo("작은 스피커예요. 소리가 맑아요.");
        assertThat(ItemSummaryTestAccess.parse("{\"summary\": \"" + "가".repeat(400) + "\"}")).hasSize(300);
    }

    @Test
    void deviceTokenRegisterAndRemoveOnlyMine() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "devone");
        TestSupport.Account b = TestSupport.newAccount(mvc, "devtwo");
        String body = "{\"token\":\"test-fcm-token-123\",\"platform\":\"android\"}";
        mvc.perform(post("/api/devices").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.fcmEnabled").value(false));
        // 남의 토큰 지우기 → 204 지만 아무 일 없음, 내 토큰 → 204
        mvc.perform(post("/api/devices/remove").header("Authorization", b.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/devices/remove").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/devices/remove").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
