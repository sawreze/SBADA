package com.sbada;

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

import com.jayway.jsonpath.JsonPath;
import com.sbada.gateway.InternalToken;

/**
 * MSA 1단계(결제 서비스 분리) — 메인 서버 쪽 확인
 *  ① 게이트웨이: /api/wallet/** 는 로그인 검사 후 결제 서비스로 전달, 결제 서비스가 꺼져 있으면 503
 *  ② 내부 API: 내부 토큰이 있어야 하고, 같은 이벤트는 한 번만 알림이 된다
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-msa-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.services.payment.url=http://127.0.0.1:9"
})
@AutoConfigureMockMvc
class PaymentServiceIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InternalToken internal;

    @Test
    void gatewayRequiresLoginAndReportsPaymentServiceDown() throws Exception {
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized());
        TestSupport.Account a = TestSupport.newAccount(mvc, "gw");
        mvc.perform(get("/api/wallet").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_SERVICE_DOWN"));
        mvc.perform(post("/api/payments/extra").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"toUserId\":1,\"amount\":1000,\"method\":\"CARD\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void internalApiNeedsTokenAndEventsAreIdempotent() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "evt");
        mvc.perform(get("/internal/users/" + a.id())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/users/" + a.id()).header(InternalToken.HEADER, internal.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(a.id()));
        mvc.perform(get("/internal/users/987654321").header(InternalToken.HEADER, internal.value()))
                .andExpect(status().isNotFound());

        String event = """
                {"eventId":"evt-%d","type":"WITHDRAWN","userId":%d,"title":"출금되었습니다",
                 "body":"카카오뱅크 ****5678 계좌로 30,000원을 보냈어요. (시연 · 실제 송금 없음)","refId":3}
                """.formatted(a.id(), a.id());
        mvc.perform(post("/internal/events").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/events").header(InternalToken.HEADER, internal.value())
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(post("/internal/events").header(InternalToken.HEADER, internal.value())
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));

        String list = mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> titles = JsonPath.read(list, "$.notifications[?(@.type == 'WALLET')].title");
        org.assertj.core.api.Assertions.assertThat(titles).containsExactly("출금되었습니다"); // 두 번 와도 한 번
    }
}
