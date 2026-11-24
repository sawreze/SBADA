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
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.sbada.payment.events.OutboxRepository;
import com.sbada.payment.wallet.EscrowService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 금융 백엔드(2026-11-25): 안전결제(보관 → 정산 / 환불) · 자동 정산 · 1원 계좌 인증 · 샌드박스
 * 메인 서버 확인은 끄고(verify-users=false) 결제 서비스만 따로 시험한다. 자동 정산 시간은 0분.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-escrow-test;DB_CLOSE_DELAY=-1",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.wallet.key-dir=./build/test-data/keys",
        "sbada.core.url=http://127.0.0.1:9",
        "sbada.core.verify-users=false",
        "sbada.events.relay-delay-ms=3600000",
        "sbada.escrow.auto-release-minutes=0",
        "sbada.escrow.sweep-delay-ms=3600000"
})
@AutoConfigureMockMvc
class EscrowApiTest {

    private static final AtomicLong IDS = new AtomicLong(5000);
    private static final AtomicLong EXCHANGES = new AtomicLong(70000);

    @Autowired
    MockMvc mvc;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    EscrowService escrow;

    private static String bearer(long userId) throws Exception {
        String b64 = Files.readString(Path.of("./build/test-data/.jwt-secret"), StandardCharsets.US_ASCII).trim();
        return "Bearer " + Jwts.builder().issuer("sbada").subject(Long.toString(userId))
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(b64))).compact();
    }

    private static String internalToken() throws Exception {
        return Files.readString(Path.of("./build/test-data/.internal-token"), StandardCharsets.US_ASCII).trim();
    }

    private ResultActions hold(long payer, long payee, long exchangeId, long amount, String method) throws Exception {
        return mvc.perform(post("/api/payments/escrow").header("Authorization", bearer(payer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchangeId\":%d,\"amount\":%d,\"method\":\"%s\",\"toUserId\":%d}"
                        .formatted(exchangeId, amount, method, payee)));
    }

    private ResultActions internal(String action, long exchangeId, String token) throws Exception {
        var req = post("/internal/escrow/" + action).contentType(MediaType.APPLICATION_JSON)
                .content("{\"exchangeId\":%d,\"reason\":\"교환이 취소되어\"}".formatted(exchangeId));
        return mvc.perform(token == null ? req : req.header("X-Internal-Token", token));
    }

    @Test
    void cardPaymentIsHeldThenReleasedToPayee() throws Exception {
        long payer = IDS.incrementAndGet();
        long payee = IDS.incrementAndGet();
        long ex = EXCHANGES.incrementAndGet();

        // ① 카드로 10,000원 + 수수료 500원 → 보관. 카드로 냈으니 내 지갑은 그대로, 상대 지갑에도 아직 안 들어감
        hold(payer, payee, ex, 10_000, "CARD")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("결제되었습니다"))
                .andExpect(jsonPath("$.balance").value(100_000))
                .andExpect(jsonPath("$.escrow.status").value("HELD"))
                .andExpect(jsonPath("$.escrow.role").value("PAYER"))
                .andExpect(jsonPath("$.escrow.total").value(10_500))
                .andExpect(jsonPath("$.transaction.type").value("ESCROW_HOLD"))
                .andExpect(jsonPath("$.transaction.amount").value(-10_500));
        mvc.perform(get("/api/wallet").header("Authorization", bearer(payee)))
                .andExpect(jsonPath("$.balance").value(100_000));
        mvc.perform(get("/api/payments/escrow").param("exchangeId", "" + ex).header("Authorization", bearer(payee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PAYEE"))
                .andExpect(jsonPath("$.statusLabel").value("보관 중"));
        mvc.perform(get("/api/payments/escrow").param("exchangeId", "" + ex)
                        .header("Authorization", bearer(IDS.incrementAndGet())))
                .andExpect(status().isNotFound()); // 남의 결제는 없는 것처럼

        // 같은 교환에 두 번 결제 → 409
        hold(payer, payee, ex, 10_000, "CARD")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PAID"));

        // ② 정산: 내부 토큰 없으면 401, 있으면 받는 사람 지갑 +10,000. 두 번 불러도 한 번만(멱등)
        internal("release", ex, null).andExpect(status().isUnauthorized());
        internal("release", ex, "wrong-token").andExpect(status().isUnauthorized());
        internal("release", ex, internalToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        internal("release", ex, internalToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/wallet").header("Authorization", bearer(payee)))
                .andExpect(jsonPath("$.balance").value(110_000))
                .andExpect(jsonPath("$.transactions[0].type").value("ESCROW_RELEASE"));

        // 정산된 건은 환불할 수 없다
        internal("refund", ex, internalToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_RELEASED"));

        // 메인 서버로 갈 이벤트에 교환 번호가 들어 있다(교환 화면의 결제 상태를 바꾸는 데 씀)
        assertThat(outbox.findByUserIdOrderByIdAsc(payee)).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("ESCROW_RELEASED");
            assertThat(e.getPayload()).contains("\"exchangeId\":" + ex);
        });
        assertThat(outbox.findByUserIdOrderByIdAsc(payer))
                .extracting(e -> e.getEventType()).contains("ESCROW_HELD", "ESCROW_RELEASED");
    }

    @Test
    void walletPaymentIsRefundedToWallet() throws Exception {
        long payer = IDS.incrementAndGet();
        long payee = IDS.incrementAndGet();
        long ex = EXCHANGES.incrementAndGet();

        // 지갑 잔액으로 내면 잔액이 줄어든다: 100,000 − 10,500 = 89,500
        hold(payer, payee, ex, 10_000, "WALLET")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(89_500))
                .andExpect(jsonPath("$.escrow.methodLabel").value("쓰바다 지갑"));

        // 교환 취소 → 환불(수수료까지 전부) → 100,000. 다시 불러도 그대로
        internal("refund", ex, internalToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        internal("refund", ex, internalToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        mvc.perform(get("/api/wallet").header("Authorization", bearer(payer)))
                .andExpect(jsonPath("$.balance").value(100_000))
                .andExpect(jsonPath("$.transactions[0].type").value("ESCROW_REFUND"))
                .andExpect(jsonPath("$.transactions[0].amount").value(10_500));
        mvc.perform(get("/api/wallet").header("Authorization", bearer(payee)))
                .andExpect(jsonPath("$.balance").value(100_000));
        internal("release", ex, internalToken())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_REFUNDED"));

        // 환불된 교환은 다시 결제할 수 있다. 잔액보다 많이 → 409, 없는 결제수단 → 400
        hold(payer, payee, ex, 1_000_000, "WALLET")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        hold(payer, payee, ex, 10_000, "BITCOIN")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_METHOD"));
        hold(payer, payer, ex, 10_000, "CARD")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_PAY"));
        hold(payer, payee, ex, 10_000, "TOSSPAY").andExpect(status().isOk());
        internal("release", 999_999_999L, internalToken())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ESCROW_NOT_FOUND"));
    }

    @Test
    void sweepAutoReleasesAfterDeadline() throws Exception {
        long payer = IDS.incrementAndGet();
        long payee = IDS.incrementAndGet();
        long ex = EXCHANGES.incrementAndGet();
        hold(payer, payee, ex, 20_000, "KAKAOPAY").andExpect(status().isOk());

        // 자동 확정 시간(테스트: 0분)이 지났으니 정산된다
        assertThat(escrow.sweep()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/payments/escrow").param("exchangeId", "" + ex).header("Authorization", bearer(payer)))
                .andExpect(jsonPath("$.status").value("RELEASED"));
        mvc.perform(get("/api/wallet").header("Authorization", bearer(payee)))
                .andExpect(jsonPath("$.balance").value(120_000));
    }

    @Test
    void oneWonVerificationRegistersEncryptedAccount() throws Exception {
        long me = IDS.incrementAndGet();
        String body = mvc.perform(post("/api/wallet/accounts/verify").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"TOSSBANK\",\"accountNumber\":\"1000-1234-5678\",\"holder\":\"임해인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("토스뱅크"))
                .andExpect(jsonPath("$.accountMasked").value("****5678"))
                .andExpect(jsonPath("$.maxAttempts").value(5))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = JsonPath.read(body, "$.verificationId");
        String memo = JsonPath.read(body, "$.demoMemo");
        assertThat(memo).matches("쓰바다 \\d{4}");
        String code = memo.substring(memo.length() - 4);
        String wrong = String.format("%04d", (Integer.parseInt(code) + 1) % 10_000);

        // 알림 이벤트로 입금자명이 간다. 계좌번호 원문은 이벤트에도 없다
        assertThat(outbox.findByUserIdOrderByIdAsc(me)).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("ACCOUNT_VERIFY_SENT");
            assertThat(e.getPayload()).contains(memo).doesNotContain("100012345678");
        });

        // 틀리면 400 + 남은 기회, 형식이 틀리면 400 VALIDATION, 맞으면 201 계좌 등록
        mvc.perform(post("/api/wallet/accounts/verify/" + id + "/confirm").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_CODE"))
                .andExpect(jsonPath("$.message").value("인증 숫자가 맞지 않아요. (남은 기회 4번)"));
        mvc.perform(post("/api/wallet/accounts/verify/" + id + "/confirm").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"12a\"}"))
                .andExpect(status().isBadRequest());
        // 남의 인증 번호로는 확인할 수 없다
        mvc.perform(post("/api/wallet/accounts/verify/" + id + "/confirm")
                        .header("Authorization", bearer(IDS.incrementAndGet()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/wallet/accounts/verify/" + id + "/confirm").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankName").value("토스뱅크"))
                .andExpect(jsonPath("$.holder").value("임해인"));
        mvc.perform(post("/api/wallet/accounts/verify/" + id + "/confirm").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"%s\"}".formatted(code)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
        mvc.perform(get("/api/wallet").header("Authorization", bearer(me)))
                .andExpect(jsonPath("$.accounts[0].accountMasked").value("****5678"));

        // 이미 등록된 계좌로 다시 인증 시작 → 409
        mvc.perform(post("/api/wallet/accounts/verify").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"TOSSBANK\",\"accountNumber\":\"100012345678\",\"holder\":\"임해인\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"));
    }

    @Test
    void sandboxResetRestoresDemoBalance() throws Exception {
        long me = IDS.incrementAndGet();
        mvc.perform(get("/api/payment/health"))
                .andExpect(jsonPath("$.mode").value("SANDBOX"))
                .andExpect(jsonPath("$.realMoney").value(false))
                .andExpect(jsonPath("$.label").value("시연 모드 · 가상 거래"));
        mvc.perform(post("/api/wallet/charge").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CARD\",\"amount\":7000}"))
                .andExpect(jsonPath("$.balance").value(107_000));
        mvc.perform(post("/api/wallet/sandbox/reset").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100_000))
                .andExpect(jsonPath("$.transaction.type").value("SANDBOX_RESET"))
                .andExpect(jsonPath("$.transaction.amount").value(-7000));
    }
}
