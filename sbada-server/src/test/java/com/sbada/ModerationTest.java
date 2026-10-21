package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.sbada.ai.ProfanityFilter;

/** 콘텐츠 검사: 욕설 제목 → 보류(HELD) + 주인에게 ITEM_HELD 알림, 남에게는 안 보임. AI 는 끄고(fallback) 금칙어 검사로 확인한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-moderation-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class ModerationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void profanityFilterCatchesVariants() {
        assertThat(ProfanityFilter.find("씨 발 헤드폰")).isNotNull();
        assertThat(ProfanityFilter.find("시1발 팝니다")).isNotNull();
        assertThat(ProfanityFilter.find("블루투스 헤드폰", "깨끗해요")).isNull();
    }

    @Test
    void geminiVerdictParsing() throws Exception {
        var parse = Class.forName("com.sbada.ai.ModerationService").getDeclaredMethod("parse", Map.class);
        parse.setAccessible(true);
        Object ok = parse.invoke(null, Map.of("allowed", true, "category", "OK", "reason", ""));
        Object bad = parse.invoke(null, Map.of("allowed", false, "category", "sexual", "reason", ""));
        assertThat(ok.toString()).contains("allowed=true");
        assertThat(bad.toString()).contains("allowed=false").contains("SEXUAL").contains("선정적");
    }

    /** 정든 물건에게 보내는 메시지(V8): 저장 · 응답에 그대로, 60자 초과 400, 욕설이면 보류 */
    @Test
    void farewellMessageIsSavedAndChecked() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "farewell");
        long kind = createWithMessage(a, "고마웠어 헤드폰", "그동안 고마웠어, 좋은 주인 만나");
        mvc.perform(get("/api/items/" + kind))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.farewellMessage").value("그동안 고마웠어, 좋은 주인 만나"));

        long img = TestSupport.uploadImage(mvc, a);
        long analysis = TestSupport.analyze(mvc, a, img, "DIGITAL", "GOOD");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/items")
                        .header("Authorization", a.bearer()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"긴 메시지","category":"DIGITAL","condition":"GOOD","imageIds":[%d],"analysisId":%d,
                                 "wishCategories":[],"farewellMessage":"%s"}
                                """.formatted(img, analysis, "가".repeat(61))))
                .andExpect(status().isBadRequest());

        long rude = createWithMessage(a, "평범한 스피커", "씨발 잘가라");
        String status = "";
        for (int i = 0; i < 50 && !"HELD".equals(status); i++) {
            Thread.sleep(100);
            String body = mvc.perform(get("/api/items/" + rude).header("Authorization", a.bearer()))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            status = JsonPath.read(body, "$.status");
        }
        assertThat(status).isEqualTo("HELD");
    }

    private long createWithMessage(TestSupport.Account a, String title, String message) throws Exception {
        long img = TestSupport.uploadImage(mvc, a);
        long analysis = TestSupport.analyze(mvc, a, img, "DIGITAL", "GOOD");
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/items")
                        .header("Authorization", a.bearer()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","category":"DIGITAL","condition":"GOOD","imageIds":[%d],"analysisId":%d,
                                 "wishCategories":[],"farewellMessage":"%s"}
                                """.formatted(title, img, analysis, message)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.id");
        return id.longValue();
    }

    @Test
    void profaneTitleIsHeldAndOwnerIsNotified() throws Exception {
        TestSupport.Account owner = TestSupport.newAccount(mvc, "modown");
        TestSupport.Account other = TestSupport.newAccount(mvc, "modoth");
        long clean = TestSupport.createItem(mvc, owner, "깨끗한 헤드폰", "DIGITAL", "GOOD", "[]");
        long bad = TestSupport.createItem(mvc, owner, "씨발 헤드폰", "DIGITAL", "GOOD", "[]");

        // 검사는 등록 뒤 비동기로 돈다 → 최대 5초 기다린다
        String status = "";
        for (int i = 0; i < 50 && !"HELD".equals(status); i++) {
            Thread.sleep(100);
            String body = mvc.perform(get("/api/items/" + bad).header("Authorization", owner.bearer()))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            status = JsonPath.read(body, "$.status");
        }
        assertThat(status).isEqualTo("HELD");

        // 주인: 상세에서 보류 이유가 보이고, 알림함에 ITEM_HELD
        mvc.perform(get("/api/items/" + bad).header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationReason").isString());
        mvc.perform(get("/api/notifications").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.notifications[0].type").value("ITEM_HELD"))
                .andExpect(jsonPath("$.notifications[0].refId").value(bad));

        // 남: 상세 404, 목록 · 검색에 없음. 깨끗한 물건은 그대로 보인다
        mvc.perform(get("/api/items/" + bad).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/api/items/" + bad)).andExpect(status().isNotFound());
        String list = mvc.perform(get("/api/items").param("q", "헤드폰"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.List<Integer> ids = JsonPath.read(list, "$.items[*].id");
        assertThat(ids).contains((int) clean).doesNotContain((int) bad);
    }
}
