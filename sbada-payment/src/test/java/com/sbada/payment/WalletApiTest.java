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

import com.jayway.jsonpath.JsonPath;
import com.sbada.payment.crypto.AccountCipher;
import com.sbada.payment.events.OutboxRepository;
import com.sbada.payment.wallet.WalletAccount;
import com.sbada.payment.wallet.WalletAccountRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** 결제 서비스: 지갑 · 계좌 암호화 · 출금 · 추가 요금(수수료 5%) · 아웃박스 이벤트 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-payment-test;DB_CLOSE_DELAY=-1",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.wallet.key-dir=./build/test-data/keys",
        "sbada.core.url=http://127.0.0.1:9",
        "sbada.core.verify-users=false",
        "sbada.events.relay-delay-ms=3600000"
})
@AutoConfigureMockMvc
class WalletApiTest {

    private static final AtomicLong IDS = new AtomicLong(1000);

    @Autowired
    MockMvc mvc;
    @Autowired
    WalletAccountRepository accounts;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    AccountCipher cipher;

    /** 메인 서버가 발급하는 것과 같은 모양의 토큰(같은 키 파일로 서명) */
    private static String token(long userId) throws Exception {
        String b64 = Files.readString(Path.of("./build/test-data/.jwt-secret"), StandardCharsets.US_ASCII).trim();
        return Jwts.builder().issuer("sbada").subject(Long.toString(userId))
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(b64))).compact();
    }

    private static String bearer(long userId) throws Exception {
        return "Bearer " + token(userId);
    }

    @Test
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/wallet")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/api/wallet").header("Authorization", "Bearer x.y.z")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        mvc.perform(get("/api/payment/health")).andExpect(status().isOk());
    }

    @Test
    void accountNumberIsEncryptedAndWithdrawWorks() throws Exception {
        long me = IDS.incrementAndGet();
        mvc.perform(get("/api/wallet").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.balance").value(100000))
                .andExpect(jsonPath("$.feePercent").value(5));

        String body = mvc.perform(post("/api/wallet/accounts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"KAKAOBANK\",\"accountNumber\":\"3333-01-2345678\",\"holder\":\"임해인\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bankName").value("카카오뱅크"))
                .andExpect(jsonPath("$.accountMasked").value("****5678"))
                .andReturn().getResponse().getContentAsString();
        int accountId = JsonPath.read(body, "$.id");

        // DB 에는 원문이 없고, RSA 개인키로만 복원된다
        WalletAccount saved = accounts.findById((long) accountId).orElseThrow();
        assertThat(saved.getEncAccount()).doesNotContain("33330123").doesNotContain("2345678");
        assertThat(saved.getEncKey()).hasSizeGreaterThan(400); // RSA-3072 = 384바이트 → Base64 512자
        assertThat(cipher.open(saved.getEncAccount(), saved.getEncKey())).isEqualTo("3333012345678");
        assertThat(saved.getAccountHash()).hasSize(64).isEqualTo(cipher.fingerprint("3333012345678"));

        // 같은 계좌 다시 등록 → 409, 잘못된 번호 → 400
        mvc.perform(post("/api/wallet/accounts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"KAKAOBANK\",\"accountNumber\":\"333301 2345678\",\"holder\":\"임해인\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"));
        mvc.perform(post("/api/wallet/accounts").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bankCode\":\"TOSSBANK\",\"accountNumber\":\"12ab\",\"holder\":\"임해인\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_NUMBER"));

        // 출금 30,000원 → "출금되었습니다", 잔액 70,000
        mvc.perform(post("/api/wallet/withdraw").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":30000}".formatted(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("출금되었습니다"))
                .andExpect(jsonPath("$.balance").value(70000))
                .andExpect(jsonPath("$.transaction.type").value("WITHDRAW"))
                .andExpect(jsonPath("$.transaction.amount").value(-30000));
        assertThat(outbox.findByUserIdOrderByIdAsc(me)).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo("WITHDRAWN");
            assertThat(e.getPayload()).contains("출금되었습니다").doesNotContain("3333012345678");
        });

        // 잔액보다 많이 → 409, 남의 계좌 → 404, 최소 금액 미만 → 400
        mvc.perform(post("/api/wallet/withdraw").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":80000}".formatted(accountId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        mvc.perform(post("/api/wallet/withdraw").header("Authorization", bearer(IDS.incrementAndGet()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":1000}".formatted(accountId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(post("/api/wallet/withdraw").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"amount\":500}".formatted(accountId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void extraPaymentAddsFivePercentFeeAndCreditsReceiver() throws Exception {
        long payer = IDS.incrementAndGet();
        long receiver = IDS.incrementAndGet();
        mvc.perform(get("/api/payments/quote").param("amount", "10000").header("Authorization", bearer(payer)))
                .andExpect(jsonPath("$.fee").value(500)).andExpect(jsonPath("$.total").value(10500));

        mvc.perform(post("/api/payments/extra").header("Authorization", bearer(payer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":%d,\"amount\":10000,\"method\":\"KAKAOPAY\"}".formatted(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("결제되었습니다"))
                .andExpect(jsonPath("$.transaction.amount").value(-10500))
                .andExpect(jsonPath("$.transaction.fee").value(500))
                .andExpect(jsonPath("$.transaction.methodLabel").value("카카오페이"))
                .andExpect(jsonPath("$.balance").value(100000)); // 카드 · 간편결제로 냈으니 지갑은 그대로

        mvc.perform(get("/api/wallet").header("Authorization", bearer(receiver)))
                .andExpect(jsonPath("$.balance").value(110000))
                .andExpect(jsonPath("$.transactions[0].type").value("RECEIVE"));
        assertThat(outbox.findByUserIdOrderByIdAsc(receiver))
                .anySatisfy(e -> assertThat(e.getEventType()).isEqualTo("PAYMENT_RECEIVED"));

        mvc.perform(post("/api/payments/extra").header("Authorization", bearer(payer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":%d,\"amount\":10000,\"method\":\"CARD\"}".formatted(payer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELF_PAY"));
        mvc.perform(post("/api/payments/extra").header("Authorization", bearer(payer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUserId\":%d,\"amount\":10000,\"method\":\"BITCOIN\"}".formatted(receiver)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_METHOD"));
    }

    @Test
    void chargeWithTossPay() throws Exception {
        long me = IDS.incrementAndGet();
        mvc.perform(post("/api/wallet/charge").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"TOSSPAY\",\"amount\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(105000))
                .andExpect(jsonPath("$.transaction.methodLabel").value("토스페이"));
    }
}
