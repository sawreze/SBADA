package com.sbada.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.sbada.payment.toss.TossClient;
import com.sbada.payment.toss.TossService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 2026-11-25 토스페이먼츠(테스트 키) 연동 — 토스 API 는 이 테스트 안의 가짜(FakeToss)로 대신한다.
 *  카드: prepare → confirm(DONE) → 안전결제 보관(TOSS_CARD) → 내부 환불 → 토스 결제 취소 호출
 *  가상계좌: confirm(WAITING_FOR_DEPOSIT, 계좌번호) → 웹훅(본문은 안 믿고 토스에 다시 조회) → 입금 확인 → 보관(TOSS_VA)
 *  앱이 TOSS_CARD 를 /api/payments/escrow 로 직접 보내 '결제한 척'하는 것은 막힌다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-toss-test;DB_CLOSE_DELAY=-1",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.wallet.key-dir=./build/test-data/keys",
        "sbada.core.url=http://127.0.0.1:9",
        "sbada.core.verify-users=false",
        "sbada.events.relay-delay-ms=3600000",
        "sbada.escrow.sweep-delay-ms=3600000",
        "sbada.toss.poll-ms=3600000",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
class TossPaymentTest {

    private static final AtomicLong IDS = new AtomicLong(9000);
    private static final AtomicLong EXCHANGES = new AtomicLong(90000);

    /** 가짜 토스: 주문번호 → 결제 상태. 카드는 바로 DONE, 가상계좌는 입금 전까지 WAITING_FOR_DEPOSIT */
    static class FakeToss extends TossClient {
        final Map<String, String> status = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        FakeToss() {
            super("test_ck_fake", "test_sk_fake");
        }

        @Override
        public Map<String, Object> confirm(String paymentKey, String orderId, long amount) {
            calls.add("confirm " + orderId + " " + amount);
            String s = paymentKey.startsWith("va-") ? "WAITING_FOR_DEPOSIT" : "DONE";
            status.put(orderId, s);
            return payment(paymentKey, orderId, s);
        }

        @Override
        public Map<String, Object> getByOrderId(String orderId) {
            calls.add("get " + orderId);
            return payment(orderId.startsWith("x") ? "pk" : "va-pk", orderId, status.getOrDefault(orderId, "READY"));
        }

        @Override
        public Map<String, Object> cancel(String paymentKey, String reason) {
            calls.add("cancel " + paymentKey);
            return Map.of("status", "CANCELED");
        }

        private static Map<String, Object> payment(String key, String orderId, String s) {
            return Map.of("paymentKey", key, "orderId", orderId, "status", s,
                    "virtualAccount", Map.of("bankCode", "88", "accountNumber", "X6008025675", "customerName", "테스트",
                            "dueDate", "2026-11-26T12:00:00+09:00"));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeToss fakeToss() {
            return new FakeToss();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    FakeToss toss;
    @Autowired
    TossService service;

    private static String bearer(long userId) throws Exception {
        String b64 = Files.readString(Path.of("./build/test-data/.jwt-secret"), StandardCharsets.US_ASCII).trim();
        return "Bearer " + Jwts.builder().issuer("sbada").subject(Long.toString(userId))
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(b64))).compact();
    }

    private static String internalToken() throws Exception {
        return Files.readString(Path.of("./build/test-data/.internal-token"), StandardCharsets.US_ASCII).trim();
    }

    private ResultActions json(long user, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String prepare(long payer, long payee, long ex, String method) throws Exception {
        String res = json(payer, "/api/payments/toss/prepare",
                "{\"exchangeId\":%d,\"method\":\"%s\",\"amount\":20000,\"toUserId\":%d}".formatted(ex, method, payee))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21_000))
                .andExpect(jsonPath("$.clientKey").value("test_ck_fake"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(res, "$.orderId");
    }

    @Test
    void cardIsConfirmedHeldAndCanceledOnRefund() throws Exception {
        long payer = IDS.incrementAndGet(), payee = IDS.incrementAndGet(), ex = EXCHANGES.incrementAndGet();
        mvc.perform(get("/api/payments/toss/config").header("Authorization", bearer(payer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.testMode").value(true));
        String orderId = prepare(payer, payee, ex, "CARD");
        // 금액을 바꿔 치면 거절, 남의 주문도 거절
        json(payer, "/api/payments/toss/confirm", "{\"paymentKey\":\"pk1\",\"orderId\":\"%s\",\"amount\":100}".formatted(orderId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));
        json(payee, "/api/payments/toss/confirm", "{\"paymentKey\":\"pk1\",\"orderId\":\"%s\",\"amount\":21000}".formatted(orderId))
                .andExpect(status().isForbidden());
        // 승인 → DONE → 안전결제 보관
        json(payer, "/api/payments/toss/confirm", "{\"paymentKey\":\"pk1\",\"orderId\":\"%s\",\"amount\":21000}".formatted(orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/payments/escrow").param("exchangeId", "" + ex).header("Authorization", bearer(payee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HELD"));
        // 같은 승인이 다시 와도 한 번만
        json(payer, "/api/payments/toss/confirm", "{\"paymentKey\":\"pk1\",\"orderId\":\"%s\",\"amount\":21000}".formatted(orderId))
                .andExpect(status().isOk());
        assertThat(toss.calls.stream().filter(c -> c.startsWith("confirm " + orderId)).count()).isEqualTo(1);
        // 이미 결제된 교환은 새 주문 불가
        json(payer, "/api/payments/toss/prepare", "{\"exchangeId\":%d,\"method\":\"CARD\",\"amount\":20000,\"toUserId\":%d}".formatted(ex, payee))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));
        // 교환 취소 · 만료로 환불 → 토스 결제 취소 호출
        mvc.perform(post("/internal/escrow/refund").header("X-Internal-Token", internalToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"exchangeId\":%d,\"reason\":\"기간 만료\"}".formatted(ex)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(toss.calls).contains("cancel pk1");
    }

    @Test
    void virtualAccountWaitsForDepositThenWebhookHolds() throws Exception {
        long payer = IDS.incrementAndGet(), payee = IDS.incrementAndGet(), ex = EXCHANGES.incrementAndGet();
        String orderId = prepare(payer, payee, ex, "VIRTUAL_ACCOUNT");
        json(payer, "/api/payments/toss/confirm", "{\"paymentKey\":\"va-1\",\"orderId\":\"%s\",\"amount\":21000}".formatted(orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_DEPOSIT"))
                .andExpect(jsonPath("$.virtualAccount.bank").value("신한은행"))
                .andExpect(jsonPath("$.virtualAccount.accountNumber").value("X6008025675"));
        // 입금 전: 보관 없음, 다시 prepare 하면 같은 계좌를 다시 보여 준다
        mvc.perform(get("/api/payments/escrow").param("exchangeId", "" + ex).header("Authorization", bearer(payer)))
                .andExpect(status().isNotFound());
        json(payer, "/api/payments/toss/prepare", "{\"exchangeId\":%d,\"method\":\"VIRTUAL_ACCOUNT\",\"amount\":20000,\"toUserId\":%d}".formatted(ex, payee))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(orderId));
        // 가짜 웹훅(본문 상태 DONE 이라고 거짓말) → 토스에 다시 물어보면 아직 입금 전 → 그대로
        mvc.perform(post("/api/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"%s\",\"status\":\"DONE\"}".formatted(orderId)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/payments/toss/orders").param("exchangeId", "" + ex).header("Authorization", bearer(payer)))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_DEPOSIT"));
        // 실제로 입금됨(토스가 DONE) → 웹훅 → 보관(TOSS_VA)
        toss.status.put(orderId, "DONE");
        mvc.perform(post("/api/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"DEPOSIT_CALLBACK\",\"data\":{\"orderId\":\"%s\"}}".formatted(orderId)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/payments/toss/orders").param("exchangeId", "" + ex).header("Authorization", bearer(payee)))
                .andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(get("/api/payments/escrow").param("exchangeId", "" + ex).header("Authorization", bearer(payee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void appCannotFakeTossPaymentThroughEscrowEndpoint() throws Exception {
        long payer = IDS.incrementAndGet(), payee = IDS.incrementAndGet(), ex = EXCHANGES.incrementAndGet();
        json(payer, "/api/payments/escrow", "{\"exchangeId\":%d,\"amount\":20000,\"method\":\"TOSS_CARD\",\"toUserId\":%d}".formatted(ex, payee))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_METHOD"));
    }
}
