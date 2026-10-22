package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.sbada.common.Json;

/** 동네지도 매치도 GET /api/feed/scores?ids= — 피드 상위에 없는 물건도 점수가 나오고, 피드와 같은 값이어야 한다 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-feed-scores-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class FeedScoresApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    @SuppressWarnings("unchecked")
    void scoresMatchFeedAndSkipMine() throws Exception {
        TestSupport.Account seller = TestSupport.newAccount(mvc, "scoresell");
        TestSupport.Account me = TestSupport.newAccount(mvc, "scoreme");
        long a = TestSupport.createItem(mvc, seller, "점수 테스트 키보드", "DIGITAL", "GOOD", "[]");
        long b = TestSupport.createItem(mvc, seller, "점수 테스트 책", "BOOK", "GOOD", "[]");
        long mine = TestSupport.createItem(mvc, me, "내 물건 마우스", "DIGITAL", "GOOD", "[]");

        mvc.perform(get("/api/feed/scores").param("ids", a + "," + b)).andExpect(status().isUnauthorized());

        String body = mvc.perform(get("/api/feed/scores").param("ids", a + "," + b + "," + mine + ",999999")
                        .header("Authorization", me.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> scores = (Map<String, Object>) Json.readMap(body).get("scores");
        assertThat(scores).containsOnlyKeys(String.valueOf(a), String.valueOf(b)); // 내 물건 · 없는 물건은 빠짐

        // 같은 물건의 피드 점수와 같다
        String feed = mvc.perform(get("/api/feed").param("limit", "60").header("Authorization", me.bearer()))
                .andReturn().getResponse().getContentAsString();
        for (Object o : (java.util.List<Object>) Json.readMap(feed).get("items")) {
            Map<String, Object> rec = (Map<String, Object>) o;
            Object id = ((Map<String, Object>) rec.get("item")).get("id");
            if (scores.containsKey(String.valueOf(id))) {
                assertThat(((Number) scores.get(String.valueOf(id))).intValue())
                        .isEqualTo(((Number) rec.get("score")).intValue());
            }
        }
    }
}
