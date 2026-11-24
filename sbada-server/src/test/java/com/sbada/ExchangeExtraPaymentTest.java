package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.sbada.gateway.InternalToken;

/**
 * 금융 백엔드(2026-11-25) — 메인 서버 쪽: 교환 추가 금액 + 안전결제 상태
 *  ① 추가 금액 제안 · 요구(두 당사자) → ② 결제 이벤트(ESCROW_HELD)로 PAID_HELD → ③ 성사 → ④ 낸 사람이 거래 완료 → 정산 호출
 *  취소 · 거절되면 환불 호출(REFUND_PENDING → 이벤트로 REFUNDED), 받는 사람이 돌려주기, 결제와 취소가 엇갈린 경우.
 * 결제 서비스는 이 테스트 안의 가짜 HTTP 서버로 대신한다(받은 내부 호출을 기록).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-extra-pay-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/extra-pay-uploads",
        "sbada.internal.token-file=./build/test-data/.internal-token"
})
@AutoConfigureMockMvc
class ExchangeExtraPaymentTest {

    private static final java.util.concurrent.atomic.AtomicLong EVENTS = new java.util.concurrent.atomic.AtomicLong();
    /** 결제 서버(가짜 gRPC EscrowService)로 간 정산 · 환불 호출: "release 7" · "refund 7 이유" */
    private static final List<String> CALLS = FakeServices.ESCROW_CALLS;

    @Autowired
    MockMvc mvc;
    @Autowired
    InternalToken internal;

    private record Deal(TestSupport.Account requester, TestSupport.Account owner, long exchangeId) {
    }

    /** 두 사람이 물건을 하나씩 올리고, requester 가 추가 금액 조건과 함께 교환을 요청한다 */
    private Deal newDeal(String prefix, long extraAmount, String extraPayer) throws Exception {
        TestSupport.Account req = TestSupport.newAccount(mvc, prefix);
        TestSupport.Account own = TestSupport.newAccount(mvc, prefix);
        long mine = TestSupport.createItem(mvc, req, "추가금 내 물건", "DIGITAL", "GOOD", "[]");
        long theirs = TestSupport.createItem(mvc, own, "추가금 상대 물건", "DIGITAL", "LIKE_NEW", "[]");
        String body = mvc.perform(post("/api/exchanges").header("Authorization", req.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d,\"extraAmount\":%d,\"extraPayer\":\"%s\"}"
                                .formatted(theirs, mine, extraAmount, extraPayer)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.id");
        return new Deal(req, own, id.longValue());
    }

    /** 결제 서비스가 보내는 안전결제 이벤트 흉내 */
    private ResultActions paymentEvent(String type, long userId, long exchangeId) throws Exception {
        String json = """
                {"eventId":"test-%d","type":"%s","userId":%d,"title":"결제되었습니다","body":"시연","refId":1,"exchangeId":%d}
                """.formatted(EVENTS.incrementAndGet(), type, userId, exchangeId);
        return mvc.perform(post("/internal/events").header(InternalToken.HEADER, internal.value())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
    }

    private ResultActions action(TestSupport.Account who, long exchangeId, String action) throws Exception {
        return mvc.perform(post("/api/exchanges/" + exchangeId + "/" + action).header("Authorization", who.bearer()));
    }

    private ResultActions view(TestSupport.Account who, long exchangeId) throws Exception {
        return mvc.perform(get("/api/exchanges/" + exchangeId).header("Authorization", who.bearer()));
    }

    @Test
    void extraTermsPaymentAcceptAndCompleteReleasesEscrow() throws Exception {
        Deal d = newDeal("xp", 30_000, "ME");
        view(d.requester(), d.exchangeId())
                .andExpect(jsonPath("$.extraAmount").value(30_000))
                .andExpect(jsonPath("$.extraPayerId").value(d.requester().id()))
                .andExpect(jsonPath("$.extraDirection").value("I_PAY"))
                .andExpect(jsonPath("$.paymentStatus").value("NONE"));
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.extraDirection").value("I_RECEIVE"));

        // 물건 주인이 '5만 원 더 주세요'로 바꿔 요구 → 내는 사람은 여전히 요청자
        mvc.perform(post("/api/exchanges/" + d.exchangeId() + "/extra").header("Authorization", d.owner().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50000,\"payer\":\"OTHER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extraAmount").value(50_000))
                .andExpect(jsonPath("$.extraDirection").value("I_RECEIVE"));

        // 결제 서비스가 확인하는 내부 정보: 누가 · 누구에게 · 얼마
        mvc.perform(get("/internal/exchanges/" + d.exchangeId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/exchanges/" + d.exchangeId()).header(InternalToken.HEADER, internal.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.extraAmount").value(50_000))
                .andExpect(jsonPath("$.payerId").value(d.requester().id()))
                .andExpect(jsonPath("$.payeeId").value(d.owner().id()));
        mvc.perform(get("/internal/exchanges/987654321").header(InternalToken.HEADER, internal.value()))
                .andExpect(status().isNotFound());

        // 성사 전에는 거래 완료 불가
        action(d.requester(), d.exchangeId(), "complete")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_ACCEPTED"));

        // 결제 서비스 이벤트(낸 사람 · 받는 사람 둘 다 옴) → PAID_HELD 한 번만
        paymentEvent("ESCROW_HELD", d.requester().id(), d.exchangeId());
        paymentEvent("ESCROW_HELD", d.owner().id(), d.exchangeId());
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.paymentStatus").value("PAID_HELD"));

        // 결제 뒤에는 조건을 바꿀 수 없다
        mvc.perform(post("/api/exchanges/" + d.exchangeId() + "/extra").header("Authorization", d.requester().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0,\"payer\":\"ME\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));

        // 수락 → 양쪽 수령 확인: 한 사람만 누르면 아직 정산하지 않고, 둘 다 누르면 결제 서비스에 정산 호출
        action(d.owner(), d.exchangeId(), "accept").andExpect(status().isOk());
        TestSupport.Account stranger = TestSupport.newAccount(mvc, "xp");
        action(stranger, d.exchangeId(), "complete")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
        action(d.owner(), d.exchangeId(), "complete")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID_HELD"))
                .andExpect(jsonPath("$.iReceived").value(true))
                .andExpect(jsonPath("$.otherReceived").value(false));
        assertThat(CALLS).doesNotContain("release " + d.exchangeId());
        view(d.requester(), d.exchangeId())
                .andExpect(jsonPath("$.iReceived").value(false))
                .andExpect(jsonPath("$.otherReceived").value(true));
        action(d.requester(), d.exchangeId(), "complete")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.paymentStatus").value("RELEASED"))
                .andExpect(jsonPath("$.iReceived").value(true))
                .andExpect(jsonPath("$.otherReceived").value(true));
        assertThat(CALLS).contains("release " + d.exchangeId()); // 배달부(gRPC)로 결제 서버에 정산 요청

        // 정산 이벤트가 뒤늦게 와도 상태는 그대로(멱등), 두 번 눌러도 그대로
        paymentEvent("ESCROW_RELEASED", d.owner().id(), d.exchangeId());
        action(d.requester(), d.exchangeId(), "complete")
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("RELEASED"));
    }

    @Test
    void cancelAfterPaymentRequestsRefund() throws Exception {
        Deal d = newDeal("xr", 10_000, "ME");
        paymentEvent("ESCROW_HELD", d.requester().id(), d.exchangeId());

        // 결제된 채로 취소 → 커밋 뒤 결제 서비스에 환불 요청, 상태는 REFUND_PENDING
        action(d.requester(), d.exchangeId(), "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"));
        assertThat(CALLS).anySatisfy(c -> assertThat(c).startsWith("refund " + d.exchangeId() + " "));

        // 결제 서비스의 환불 이벤트 → REFUNDED
        paymentEvent("ESCROW_REFUNDED", d.requester().id(), d.exchangeId());
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
    }

    @Test
    void paymentArrivingAfterCancelIsRefundedAndPayeeCanReturnMoney() throws Exception {
        // ① 취소된 뒤에 결제 이벤트가 도착(결제와 취소가 엇갈림) → 바로 환불 요청
        Deal late = newDeal("xl", 5_000, "ME");
        action(late.requester(), late.exchangeId(), "cancel").andExpect(status().isOk());
        paymentEvent("ESCROW_HELD", late.requester().id(), late.exchangeId());
        view(late.requester(), late.exchangeId()).andExpect(jsonPath("$.paymentStatus").value("REFUND_PENDING"));
        assertThat(CALLS).anySatisfy(c -> assertThat(c).startsWith("refund " + late.exchangeId() + " "));

        // ② 상대(물건 주인)가 내는 조건: 주인이 결제 → 성사 → 받을 사람(요청자)이 돌려주기
        Deal d = newDeal("xo", 20_000, "OTHER");
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.extraDirection").value("I_PAY"));
        paymentEvent("ESCROW_HELD", d.owner().id(), d.exchangeId());
        action(d.owner(), d.exchangeId(), "accept").andExpect(status().isOk());
        action(d.owner(), d.exchangeId(), "refund-extra")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PAYEE"));
        action(d.requester(), d.exchangeId(), "refund-extra")
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("REFUNDED"));
        assertThat(CALLS).anySatisfy(c -> assertThat(c).startsWith("refund " + d.exchangeId() + " "));
    }

    @Test
    void extraTermsAreValidated() throws Exception {
        Deal d = newDeal("xv", 0, "ME");
        view(d.requester(), d.exchangeId())
                .andExpect(jsonPath("$.extraAmount").value(0))
                .andExpect(jsonPath("$.extraDirection").doesNotExist());
        TestSupport.Account stranger = TestSupport.newAccount(mvc, "xv");
        mvc.perform(post("/api/exchanges/" + d.exchangeId() + "/extra").header("Authorization", d.requester().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500,\"payer\":\"ME\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_EXTRA_AMOUNT"));
        mvc.perform(post("/api/exchanges/" + d.exchangeId() + "/extra").header("Authorization", d.requester().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000,\"payer\":\"BOTH\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/exchanges/" + d.exchangeId() + "/extra").header("Authorization", stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000,\"payer\":\"ME\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
        action(d.requester(), d.exchangeId(), "complete")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_EXTRA_TERMS"));
    }
}
