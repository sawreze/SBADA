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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * 핵심 흐름: 사진 → AI 가격 → 등록 → 가격 매칭 → 바로 바꾸기 → 알림 → 수락 → 두 물건 TRADED
 * 그리고 권한(403)·상태(409)·입력(400/415) 실패 뒤에도 저장값이 바뀌지 않는지 확인한다.
 */
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
class ItemExchangeFlowTest {

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
    void fullExchangeFlow() throws Exception {
        TestSupport.Account minji = TestSupport.newAccount(mvc, "mj");
        TestSupport.Account dohyun = TestSupport.newAccount(mvc, "dh");
        TestSupport.Account third = TestSupport.newAccount(mvc, "th");

        // 온보딩: 민지는 취미, 도현은 디지털을 원한다 → 서로 원하는 '상호성' 점수
        mvc.perform(put("/api/me/preferences").header("Authorization", minji.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"wantedCategories\":[\"HOBBY\"]}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/me/preferences").header("Authorization", dohyun.bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"wantedCategories\":[\"SPORTS\"]}"))
                .andExpect(status().isOk());

        // fallback 가격: 텐트 SPORTS·NEW 40,000 / 레고 HOBBY·NEW 35,000 / 게임칩 HOBBY·LIKE_NEW 30,000
        long airpods = TestSupport.createItem(mvc, minji, "캠핑 텐트", "SPORTS", "NEW", "[\"HOBBY\"]");
        long lego = TestSupport.createItem(mvc, dohyun, "레고 테크닉", "HOBBY", "NEW", "[\"SPORTS\"]");
        long thirdItem = TestSupport.createItem(mvc, third, "닌텐도 게임칩", "HOBBY", "LIKE_NEW", "[]");

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

        // 도현: 레고 → 에어팟 '바로 바꾸기'
        String created = mvc.perform(post("/api/exchanges").header("Authorization", dohyun.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d,\"message\":\"바꿔요!\"}".formatted(airpods, lego)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.myRole").value("REQUESTER"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long exchangeId = ((Number) JsonPath.read(created, "$.id")).longValue();
        long roomId = ((Number) JsonPath.read(created, "$.chatRoomId")).longValue();

        // 같은 조합 중복 409, 남의 물건을 내놓으면 403, 내 물건끼리 400
        mvc.perform(post("/api/exchanges").header("Authorization", dohyun.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(airpods, lego)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        mvc.perform(post("/api/exchanges").header("Authorization", dohyun.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(thirdItem, airpods)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_OWNER"));
        mvc.perform(post("/api/exchanges").header("Authorization", minji.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(airpods, airpods)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_EXCHANGE"));

        // 세 번째 사람도 에어팟에 요청 → 나중에 자동 취소되어야 한다
        String other = mvc.perform(post("/api/exchanges").header("Authorization", third.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(airpods, thirdItem)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long otherId = ((Number) JsonPath.read(other, "$.id")).longValue();

        // 민지에게 알림이 왔다
        mvc.perform(get("/api/notifications").header("Authorization", minji.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications[*].type", hasItem("EXCHANGE_REQUESTED")))
                .andExpect(jsonPath("$.unread", greaterThan(0)));
        // 채팅방에 교환 제안 카드가 생겼다
        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", minji.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].type").value("PROPOSAL"))
                .andExpect(jsonPath("$.messages[0].exchange.id").value(exchangeId));

        // 요청을 보낸 사람은 수락할 수 없다(403) → 상태는 그대로
        mvc.perform(post("/api/exchanges/" + exchangeId + "/accept").header("Authorization", dohyun.bearer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_RECEIVER"));
        mvc.perform(get("/api/items/" + airpods)).andExpect(jsonPath("$.status").value("AVAILABLE"));

        // 민지가 수락 → 교환 성사
        mvc.perform(post("/api/exchanges/" + exchangeId + "/accept").header("Authorization", minji.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.myRole").value("RECEIVER"));
        mvc.perform(get("/api/items/" + airpods)).andExpect(jsonPath("$.status").value("TRADED"));
        mvc.perform(get("/api/items/" + lego)).andExpect(jsonPath("$.status").value("TRADED"));

        // 같은 물건에 걸린 다른 요청은 자동 취소
        mvc.perform(get("/api/exchanges/" + otherId).header("Authorization", third.bearer()))
                .andExpect(jsonPath("$.status").value("CANCELED"));
        // 다시 수락하면 409
        mvc.perform(post("/api/exchanges/" + exchangeId + "/accept").header("Authorization", minji.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        // 거래된 물건에는 새 요청 409
        mvc.perform(post("/api/exchanges").header("Authorization", third.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(lego, thirdItem)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ITEM_NOT_AVAILABLE"));

        // 도현에게 '성사' 알림
        mvc.perform(get("/api/notifications").header("Authorization", dohyun.bearer()))
                .andExpect(jsonPath("$.notifications[*].type", hasItem("EXCHANGE_ACCEPTED")));
        // 교환 성사로 선호도가 학습되었다(도현의 SPORTS 선호는 1.0 유지)
        mvc.perform(get("/api/me").header("Authorization", dohyun.bearer()))
                .andExpect(jsonPath("$.preferences[*].category", hasItem("SPORTS")));
    }

    @Test
    void chatRestFlowAndMembership() throws Exception {
        TestSupport.Account owner = TestSupport.newAccount(mvc, "co");
        TestSupport.Account buyer = TestSupport.newAccount(mvc, "cb");
        TestSupport.Account stranger = TestSupport.newAccount(mvc, "cs");
        long item = TestSupport.createItem(mvc, owner, "캠핑 의자", "SPORTS", "GOOD", "[]");

        String room = mvc.perform(post("/api/chats").header("Authorization", buyer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":%d}".formatted(item)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long roomId = ((Number) JsonPath.read(room, "$.id")).longValue();

        mvc.perform(post("/api/chats/" + roomId + "/messages").header("Authorization", buyer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"안녕하세요! 아직 있나요?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("안녕하세요! 아직 있나요?"));
        mvc.perform(get("/api/chats").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.rooms[0].unread").value(1))
                .andExpect(jsonPath("$.rooms[0].partner.id").value(buyer.id()));
        mvc.perform(post("/api/chats/" + roomId + "/read").header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chats").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.rooms[0].unread").value(0));
        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ROOM_MEMBER"));
        mvc.perform(post("/api/chats").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":%d}".formatted(item)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_CHAT"));
    }
}
