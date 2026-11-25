package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.sbada.exchange.ExchangeService;
import com.sbada.exchange.ShippingService;

/**
 * 2026-11-25 사용자 요청 흐름 전체:
 *  수락 → 바로 수락 / 추가요금 요청(싼 쪽 → 비싼 쪽) → 수락 · 거절 → 결제 확인되면 자동 성사
 *  → 7일 안에 운송장 입력(진짜 번호인지 확인) → 배송 현황 → 두 물건 도착하면 정산 · 거래 완료 → 후기 → 프로필 후기 목록
 *  + 7일 만료 → 취소 · 환불 · 물건 다시 교환 가능.  택배는 키 없이 되는 '테스트 택배(TEST)'로 확인한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-deal-flow-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/deal-flow-uploads",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.tracking.sweettracker-key=",
        "sbada.demo.tools-enabled=true"
})
@AutoConfigureMockMvc
class DealFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ExchangeService exchanges;
    @Autowired
    ShippingService shipping;
    @Autowired
    JdbcTemplate jdbc;

    private record Deal(TestSupport.Account requester, TestSupport.Account owner, long exchangeId, long offered, long target) {
    }

    /** requester 의 물건(offeredPrice) 으로 owner 의 물건(targetPrice) 을 원한다 */
    private Deal newDeal(String prefix, int offeredPrice, int targetPrice) throws Exception {
        TestSupport.Account req = TestSupport.newAccount(mvc, prefix);
        TestSupport.Account own = TestSupport.newAccount(mvc, prefix);
        long mine = TestSupport.createItem(req, "보내는 물건", "DIGITAL", offeredPrice);
        long theirs = TestSupport.createItem(own, "받는 물건", "DIGITAL", targetPrice);
        String body = mvc.perform(post("/api/exchanges").header("Authorization", req.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(theirs, mine)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.id");
        return new Deal(req, own, id.longValue(), mine, theirs);
    }

    private ResultActions postJson(TestSupport.Account a, String url, String json) throws Exception {
        return mvc.perform(post(url).header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(json == null ? "{}" : json));
    }

    private ResultActions view(TestSupport.Account a, long id) throws Exception {
        return mvc.perform(get("/api/exchanges/" + id).header("Authorization", a.bearer()));
    }

    /** 테스트 택배의 '등록 뒤 지난 시간'을 10분 전으로 돌려서, 다음 조회 때 배송 완료가 되게 한다 */
    private void ageShipments(long exchangeId) {
        jdbc.update("update shipments set verified_at = dateadd('MINUTE', -10, current_timestamp), "
                + "checked_at = dateadd('MINUTE', -10, current_timestamp) where exchange_id = ?", exchangeId);
    }

    @Test
    void directAcceptStartsShippingThenDeliveryCompletesAndReviews() throws Exception {
        Deal d = newDeal("dfa", 80_000, 50_000);
        // 바로 수락 → 성사 + 운송장 단계(7일)
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/accept", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.dealStage").value("SHIPPING"))
                .andExpect(jsonPath("$.shipDeadline").isString());
        assertThat(FakeServices.status(d.target())).isEqualTo("TRADED");

        // 택배사 목록(공개): 키가 없으면 테스트 택배 + 기본 택배사, realApi=false
        mvc.perform(get("/api/shipping/carriers")).andExpect(status().isOk())
                .andExpect(jsonPath("$.carriers[0].code").value("TEST"))
                .andExpect(jsonPath("$.realApi").value(false));
        // 실제 택배사는 키가 없으면 503(번호가 틀렸다는 뜻이 아님)
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"04\",\"invoiceNo\":\"123412341234\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("TRACKING_UNAVAILABLE"));
        // 틀린 번호 → 400 "운송장 번호를 다시 입력해 주세요"
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"ABCD12345X\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVOICE"))
                .andExpect(jsonPath("$.errors.invoiceNo").exists());
        // 남은 아직 운송장 입력 전 → 후기 불가
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/review", "{\"rating\":5}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_COMPLETED"));

        // 두 사람 모두 운송장 입력(하이픈은 빼고 저장, 화면엔 가운데를 가림)
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"1234-5678-9012\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipments[0].mine").value(true))
                .andExpect(jsonPath("$.shipments[0].invoiceMasked").value("1234****9012"))
                .andExpect(jsonPath("$.shipments[0].status").value("VERIFIED"));
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"9876543210\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.shipments.length()").value(2));

        // 시간이 지나 배송 완료 → 예약 작업이 확인 → 거래 완료
        ageShipments(d.exchangeId());
        shipping.runOnce();
        view(d.requester(), d.exchangeId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.dealStage").value("COMPLETED"))
                .andExpect(jsonPath("$.shipments[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.completedAt").isString());
        // 채팅방에 배송 카드 · 완료 안내가 남는다
        String room = view(d.requester(), d.exchangeId()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number roomId = JsonPath.read(room, "$.chatRoomId");
        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", d.requester().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[?(@.type == 'SHIPPING')]").isNotEmpty());

        // 후기: 한 번만, 받은 사람 프로필에 보인다
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/review", "{\"rating\":5,\"content\":\"포장이 꼼꼼했어요\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.rating").value(5));
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/review", "{\"rating\":4}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/review", "{\"rating\":3,\"content\":\"보통\"}")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/users/" + d.owner().id() + "/reviews")).andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.average").value(5.0))
                .andExpect(jsonPath("$.reviews[0].content").value("포장이 꼼꼼했어요"))
                .andExpect(jsonPath("$.reviews[0].writer.id").value(d.requester().id()));
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.iReviewed").value(true))
                .andExpect(jsonPath("$.otherReviewed").value(true));
    }

    @Test
    void extraFeeRequestRulesDeclineThenAgreeAndPaymentStartsDeal() throws Exception {
        // 받은 사람의 물건이 더 비싸면 추가요금을 요청할 수 없다(싼 쪽만 요청)
        Deal pricier = newDeal("dfb", 30_000, 90_000);
        view(pricier.owner(), pricier.exchangeId()).andExpect(jsonPath("$.canRequestExtra").value(false));
        postJson(pricier.owner(), "/api/exchanges/" + pricier.exchangeId() + "/extra-request", "{\"amount\":10000}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXTRA_NOT_ALLOWED"));

        // 받은 사람의 물건이 더 싸다 → 요청 가능
        Deal d = newDeal("dfc", 90_000, 30_000);
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.canRequestExtra").value(true));
        // 보낸 사람은 요청할 수 없다
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":10000}")
                .andExpect(status().isForbidden());
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":500}")
                .andExpect(status().isBadRequest());
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":20000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extraRequestStatus").value("REQUESTED"))
                .andExpect(jsonPath("$.extraAmount").value(20000))
                .andExpect(jsonPath("$.canRequestExtra").value(false));
        // 답을 기다리는 동안 바로 수락 불가
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/accept", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXTRA_PENDING"));
        // 보낸 사람이 '추가요금을 낼 사람'으로 보인다
        view(d.requester(), d.exchangeId()).andExpect(jsonPath("$.extraDirection").value("I_PAY"));

        // 거절 → 금액이 지워지고 받은 사람이 다시 선택(다시 요청 가능)
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/extra-request/decline", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extraRequestStatus").value("DECLINED"))
                .andExpect(jsonPath("$.extraAmount").value(0))
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.canRequestExtra").value(true));

        // 다시 요청 → 이번엔 수락 → 결제 대기(바로 수락은 막힘)
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":15000}").andExpect(status().isOk());
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/extra-request/accept", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.extraRequestStatus").value("AGREED"));
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/accept", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXTRA_PAYMENT_PENDING"));

        // 결제 확인(결제 서버 이벤트 ESCROW_HELD) → 자동 성사 + 운송장 단계
        exchanges.applyPaymentEvent(d.exchangeId(), "ESCROW_HELD");
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID_HELD"))
                .andExpect(jsonPath("$.dealStage").value("SHIPPING"));
        assertThat(FakeServices.status(d.offered())).isEqualTo("TRADED");

        // 둘 다 운송장 → 도착 → 보관 중이던 추가요금 정산(결제 서버 release 호출)
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"1111222233\"}").andExpect(status().isOk());
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"4444555566\"}").andExpect(status().isOk());
        ageShipments(d.exchangeId());
        shipping.runOnce();
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.dealStage").value("COMPLETED"))
                .andExpect(jsonPath("$.paymentStatus").value("RELEASED"));
        assertThat(FakeServices.ESCROW_CALLS).contains("release " + d.exchangeId());
    }

    @Test
    void shippingWindowExpiresCancelsRefundsAndReopensItems() throws Exception {
        Deal d = newDeal("dfd", 90_000, 30_000);
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":10000}").andExpect(status().isOk());
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/extra-request/accept", null).andExpect(status().isOk());
        exchanges.applyPaymentEvent(d.exchangeId(), "ESCROW_HELD");
        // 한 사람만 운송장을 입력하고 7일이 지남
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"7777888899\"}").andExpect(status().isOk());
        jdbc.update("update exchange_requests set ship_deadline = dateadd('MINUTE', -1, current_timestamp) where id = ?", d.exchangeId());
        // 마감이 지나면 입력도 막힌다
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"1212121212\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIP_EXPIRED"));
        shipping.runOnce();
        view(d.requester(), d.exchangeId()).andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.dealStage").value("EXPIRED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"));
        assertThat(FakeServices.ESCROW_CALLS.stream().anyMatch(c -> c.startsWith("refund " + d.exchangeId()))).isTrue();
        assertThat(FakeServices.status(d.offered())).isEqualTo("AVAILABLE");
        assertThat(FakeServices.status(d.target())).isEqualTo("AVAILABLE");
    }

    /** 2026-11-25 시연 도구(dev 전용): 배송 완료 · 기간 만료 건너뛰기, 시연 계정 정리 */
    @Test
    void demoToolsSkipDeliveryAndExpiryAndRetireDemoAccount() throws Exception {
        Deal d = newDeal("dfe", 80_000, 50_000);
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/accept", null).andExpect(status().isOk());
        // 운송장 두 장이 없으면 배송 완료로 못 건너뛴다
        postJson(d.owner(), "/api/demo/exchanges/" + d.exchangeId() + "/deliver-now", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NEED_TWO_SHIPMENTS"));
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"3131313131\"}").andExpect(status().isOk());
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"4141414141\"}").andExpect(status().isOk());
        // 남은 만료시킬 수 없다(둘 다 입력함)
        postJson(d.owner(), "/api/demo/exchanges/" + d.exchangeId() + "/expire-now", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOTH_SHIPPED"));
        postJson(d.owner(), "/api/demo/exchanges/" + d.exchangeId() + "/deliver-now", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.dealStage").value("COMPLETED"))
                .andExpect(jsonPath("$.shipments[0].status").value("DELIVERED"));

        Deal e = newDeal("dff", 80_000, 50_000);
        postJson(e.owner(), "/api/exchanges/" + e.exchangeId() + "/accept", null).andExpect(status().isOk());
        TestSupport.Account stranger = TestSupport.newAccount(mvc, "dfg");
        postJson(stranger, "/api/demo/exchanges/" + e.exchangeId() + "/expire-now", null).andExpect(status().isForbidden());
        postJson(e.requester(), "/api/demo/exchanges/" + e.exchangeId() + "/expire-now", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.dealStage").value("EXPIRED"));
        assertThat(FakeServices.status(e.target())).isEqualTo("AVAILABLE");

        // 시연 계정이 아니면 정리 불가, demo@sbada.test 는 이메일을 비우고 같은 이메일로 다시 가입할 수 있다
        postJson(stranger, "/api/demo/retire-me", null).andExpect(status().isForbidden());
        String signup = "{\"email\":\"demo@sbada.test\",\"password\":\"study-1234\",\"nickname\":\"시연\",\"region\":\"서울\"}";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isCreated());
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo@sbada.test\",\"password\":\"study-1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(login, "$.accessToken");
        mvc.perform(post("/api/demo/retire-me").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.retired").value(true));
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup)).andExpect(status().isCreated());
    }

    /** 결제가 확인됐는데 그 사이 물건이 내려간 경우: 교환 취소 + 환불, 이벤트는 정상 처리(같은 이벤트가 계속 실패하며 막히지 않음) */
    @Test
    void paymentAfterItemGoneCancelsAndRefundsInsteadOfFailing() throws Exception {
        Deal d = newDeal("dfh", 90_000, 30_000);
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":10000}").andExpect(status().isOk());
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/extra-request/accept", null).andExpect(status().isOk());
        FakeServices.setStatus(d.target(), "HIDDEN");
        exchanges.applyPaymentEvent(d.exchangeId(), "ESCROW_HELD");
        view(d.requester(), d.exchangeId()).andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"));
        assertThat(FakeServices.ESCROW_CALLS.stream().anyMatch(c -> c.startsWith("refund " + d.exchangeId()))).isTrue();
        assertThat(FakeServices.status(d.offered())).isEqualTo("AVAILABLE");
    }
}
