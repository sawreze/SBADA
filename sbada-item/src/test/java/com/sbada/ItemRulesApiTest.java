package com.sbada;

import static org.hamcrest.Matchers.greaterThan;
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

/**
 * 물건 규칙(사진 · AI 가격 · 등록 · 수정 · 삭제)과 추천(가격 매칭 · 홈 피드) — 2026-11-25 메인 서버 ItemExchangeFlowTest 에서 옮김.
 * 취향(선호도)은 회원 서비스(가짜 gRPC, ItemTestConfig.WEIGHTS)가 알려 준다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-item-rules-test;DB_CLOSE_DELAY=-1",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key="
})
@AutoConfigureMockMvc
class ItemRulesApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void uploadRejectsFakeImage() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "img");
        MockMultipartFile fake = new MockMultipartFile("file", "fake.png", "image/png",
                "이건 PNG가 아니에요".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/images").file(fake).header("Authorization", a.bearer()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));
    }

    @Test
    void aiAnalysisDecidesPriceAndOnlyOwnerCanUseImage() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "ai");
        TestSupport.Account b = TestSupport.newAccount(mvc, "ai");
        long imageId = TestSupport.uploadImage(mvc, a);
        // fallback: DIGITAL 기준가 150,000 × LIKE_NEW 0.85 = 127,500 → 130,000 (10만원 이상은 5,000원 단위)
        mvc.perform(post("/api/ai/analyze").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":%d,\"category\":\"DIGITAL\",\"condition\":\"LIKE_NEW\"}".formatted(imageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("fallback"))
                .andExpect(jsonPath("$.price").value(130_000))
                .andExpect(jsonPath("$.category").value("DIGITAL"));
        mvc.perform(post("/api/ai/analyze").header("Authorization", b.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":%d}".formatted(imageId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_IMAGE_OWNER"));
    }

    @Test
    void priceInRequestBodyIsRejectedAndOwnerRulesApply() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "own");
        TestSupport.Account b = TestSupport.newAccount(mvc, "own");
        long imageId = TestSupport.uploadImage(mvc, a);
        // 가격은 서버가 정한다: 본문에 price 를 몰래 넣으면 400 으로 거절(7장: owner_id·status 를 넣으면 400)
        String withPrice = """
                {"title":"해리포터 세트","category":"BOOK","condition":"GOOD","imageIds":[%d],"price":999999999}
                """.formatted(imageId);
        mvc.perform(post("/api/items").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(withPrice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.errors.price").exists());
        mvc.perform(get("/api/me/items").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.items.length()").value(0));

        // 정상 등록: 앞뒤 공백 정리, 가격은 AI(fallback) 가 정한 값 BOOK 8,000 × GOOD 0.7 = 5,600 → 5,500
        String json = """
                {"title":"  해리포터 세트  ","category":"BOOK","condition":"GOOD","imageIds":[%d]}
                """.formatted(imageId);
        String body = mvc.perform(post("/api/items").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("해리포터 세트"))
                .andExpect(jsonPath("$.price").value(5_500))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long itemId = ((Number) JsonPath.read(body, "$.id")).longValue();

        String edit = "{\"title\":\"남이 고친 제목\",\"description\":null,\"wishCategories\":[]}";
        mvc.perform(put("/api/items/" + itemId).header("Authorization", b.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_OWNER"));
        mvc.perform(get("/api/items/" + itemId)).andExpect(jsonPath("$.title").value("해리포터 세트"));

        mvc.perform(post("/api/items").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"category\":\"BOOK\",\"condition\":\"GOOD\",\"imageIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.imageIds").exists());

        mvc.perform(get("/api/items/999999")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));

        mvc.perform(delete("/api/items/" + itemId).header("Authorization", a.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/items/" + itemId)).andExpect(status().isNotFound());
    }

    @Test
    void priceMatchesAndFeed() throws Exception {
        TestSupport.Account minji = TestSupport.newAccount(mvc, "mj");
        TestSupport.Account dohyun = TestSupport.newAccount(mvc, "dh");
        ItemTestConfig.WEIGHTS.put(minji.id(), Map.of("HOBBY", 1.0));
        ItemTestConfig.WEIGHTS.put(dohyun.id(), Map.of("SPORTS", 1.0));
        // fallback 가격: 텐트 SPORTS·NEW 40,000 / 레고 HOBBY·NEW 35,000
        long airpods = TestSupport.createItem(mvc, minji, "캠핑 텐트", "SPORTS", "NEW", "[\"HOBBY\"]");
        long lego = TestSupport.createItem(mvc, dohyun, "레고 테크닉", "HOBBY", "NEW", "[\"SPORTS\"]");

        // 가격 매칭: 민지의 텐트 기준(0.5~2배 = 2만~8만원)으로 레고가 후보에 나오고 점수·근거가 있다
        mvc.perform(get("/api/items/" + airpods + "/matches").header("Authorization", minji.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("price-match-v1"))
                .andExpect(jsonPath("$.matches[*].item.id", hasItem((int) lego)))
                .andExpect(jsonPath("$.matches[0].score", greaterThan(0)))
                .andExpect(jsonPath("$.matches[0].factors.length()").value(5));
        // 남의 물건 매칭은 볼 수 없다
        mvc.perform(get("/api/items/" + airpods + "/matches").header("Authorization", dohyun.bearer()))
                .andExpect(status().isForbidden());

        // 홈 피드
        mvc.perform(get("/api/feed").header("Authorization", minji.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].item.id", hasItem((int) lego)));

    }
}
