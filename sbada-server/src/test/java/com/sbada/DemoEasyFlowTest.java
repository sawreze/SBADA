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
 * 2026-11-25 시연 모드(sbada.demo.easy-flow): 운송장은 아무 번호나, 상대 운송장 자동 입력, 배송 완료 · 지급 안내까지.
 * (아래는 DealFlowTest 와 같은 준비 코드)
 * 2026-11-25 사용자 요청 흐름 전체:
 *  수락 → 바로 수락 / 추가요금 요청(싼 쪽 → 비싼 쪽) → 수락 · 거절 → 결제 확인되면 자동 성사
 *  → 7일 안에 운송장 입력(진짜 번호인지 확인) → 배송 현황 → 두 물건 도착하면 정산 · 거래 완료 → 후기 → 프로필 후기 목록
 *  + 7일 만료 → 취소 · 환불 · 물건 다시 교환 가능.  택배는 키 없이 되는 '테스트 택배(TEST)'로 확인한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-demo-easy-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/deal-flow-uploads",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.tracking.sweettracker-key=",
        "sbada.demo.tools-enabled=true",
        "sbada.demo.easy-flow=true"
})
@AutoConfigureMockMvc
class DemoEasyFlowTest {

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
    void easyFlowAcceptsAnyInvoiceFillsOtherSideAndEndsWithPayoutMessage() throws Exception {
        mvc.perform(get("/api/config/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.easyFlow").value(true));
        Deal d = newDeal("dez", 90_000, 30_000);
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/extra-request", "{\"amount\":10000}").andExpect(status().isOk());
        postJson(d.requester(), "/api/exchanges/" + d.exchangeId() + "/extra-request/accept", null).andExpect(status().isOk());
        exchanges.applyPaymentEvent(d.exchangeId(), "ESCROW_HELD");
        // 아무 번호나 → 접수, 상대 운송장도 자동으로 들어간다
        postJson(d.owner(), "/api/exchanges/" + d.exchangeId() + "/shipment", "{\"carrierCode\":\"TEST\",\"invoiceNo\":\"abc\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipments.length()").value(2));
        ageShipments(d.exchangeId());
        shipping.runOnce();
        view(d.owner(), d.exchangeId()).andExpect(jsonPath("$.dealStage").value("COMPLETED"))
                .andExpect(jsonPath("$.paymentStatus").value("RELEASED"));
        String ex = view(d.owner(), d.exchangeId()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number roomId = JsonPath.read(ex, "$.chatRoomId");
        mvc.perform(get("/api/chats/" + roomId + "/messages").header("Authorization", d.owner().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[?(@.content =~ /.*지급되었어요.*/)]").isNotEmpty())
                .andExpect(jsonPath("$.messages[?(@.content =~ /.*자동으로 입력.*/)]").isNotEmpty());
    }
}
