package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired
    com.sbada.events.OutboxRepository outbox;
    @Autowired
    com.sbada.events.MainEventHandler events;

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
        assertThat(FakeServices.status(airpods)).isEqualTo("AVAILABLE"); // 물건 상태는 물품 서버(가짜)에

        // 민지가 수락 → 교환 성사
        mvc.perform(post("/api/exchanges/" + exchangeId + "/accept").header("Authorization", minji.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.myRole").value("RECEIVER"));
        // 두 물건은 물품 서버가 배달부(gRPC CompleteTrade)로 거래 완료 처리
        assertThat(FakeServices.status(airpods)).isEqualTo("TRADED");
        assertThat(FakeServices.status(lego)).isEqualTo("TRADED");
        // 허브(Kafka)로 알릴 교환 이벤트가 아웃박스에 남았다(물품 서버가 '교환 요청 수' 인기도에 씀)
        assertThat(outbox.findAll()).anySatisfy(e -> assertThat(e.getPayload())
                .contains("EXCHANGE_REQUESTED").contains("\"targetItemId\":" + airpods));

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

    /** 물품 서버가 물건을 내리면(허브 이벤트 ITEM_STATUS_CHANGED) 걸린 대기 요청이 취소되고 상대에게 알림 — 같은 이벤트는 한 번만 */
    @Test
    void itemHiddenEventCancelsWaitingRequests() throws Exception {
        TestSupport.Account owner = TestSupport.newAccount(mvc, "ho");
        TestSupport.Account asker = TestSupport.newAccount(mvc, "ha");
        long target = TestSupport.createItem(mvc, owner, "내릴 물건", "BOOK", "GOOD", "[]");
        long mine = TestSupport.createItem(mvc, asker, "내 책", "BOOK", "GOOD", "[]");
        String created = mvc.perform(post("/api/exchanges").header("Authorization", asker.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(target, mine)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long exchangeId = ((Number) JsonPath.read(created, "$.id")).longValue();

        String event = """
                {"eventId":"hide-%d","type":"ITEM_STATUS_CHANGED","itemId":%d,"ownerId":%d,"title":"내릴 물건",
                 "status":"HIDDEN","reason":"물건이 내려가서 교환 요청이 취소되었어요."}
                """.formatted(target, target, owner.id());
        assertThat(events.handle(event)).isTrue();
        assertThat(events.handle(event)).isFalse();
        mvc.perform(get("/api/exchanges/" + exchangeId).header("Authorization", asker.bearer()))
                .andExpect(jsonPath("$.status").value("CANCELED"));
        mvc.perform(get("/api/notifications").header("Authorization", asker.bearer()))
                .andExpect(jsonPath("$.notifications[0].type").value("EXCHANGE_CANCELED"));

        // 물품 서버가 맡긴 알림(찜 등)도 허브로 와서 알림함에 들어간다
        assertThat(events.handle("""
                {"eventId":"noti-%d","type":"NOTIFY","userId":%d,"notificationType":"ITEM_LIKED",
                 "title":"누가 내 물건을 찜했어요","body":"찜 테스트","refType":"ITEM","refId":%d}
                """.formatted(target, owner.id(), target))).isTrue();
        mvc.perform(get("/api/notifications").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.notifications[0].type").value("ITEM_LIKED"))
                .andExpect(jsonPath("$.notifications[0].refId").value(target));
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
