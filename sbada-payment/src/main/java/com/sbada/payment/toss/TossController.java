package com.sbada.payment.toss;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.payment.auth.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 토스페이먼츠(테스트 키) — 앱은 메인 서버(8097) /api/payments/** 로 부르고, 게이트웨이가 여기로 넘긴다.
 *  GET  /api/payments/toss/config                 (🔒) {enabled, clientKey, testMode, label}
 *  POST /api/payments/toss/prepare                (🔒) {exchangeId, method: CARD|VIRTUAL_ACCOUNT} → 주문(결제창에 넘길 값)
 *  POST /api/payments/toss/confirm                (🔒) {paymentKey, orderId, amount} → 승인(카드 DONE · 가상계좌 계좌번호)
 *  GET  /api/payments/toss/orders?exchangeId=     (🔒) 이 교환의 마지막 토스 주문(가상계좌 입금 대기 다시 보기)
 *  POST /api/payments/toss/webhook                (공개) 토스 웹훅 — 본문은 믿지 않고 토스에 다시 조회
 */
@RestController
public class TossController {

    private final TossService toss;

    public TossController(TossService toss) {
        this.toss = toss;
    }

    public record ConfirmRequest(@NotBlank String paymentKey, @NotBlank String orderId, @NotNull Long amount) {
    }

    @GetMapping("/api/payments/toss/config")
    public TossService.ConfigView config(@CurrentUser long userId) {
        return toss.config();
    }

    @PostMapping("/api/payments/toss/prepare")
    public TossService.OrderView prepare(@CurrentUser long userId, @RequestBody TossService.PrepareRequest in) {
        return toss.prepare(userId, in);
    }

    @PostMapping("/api/payments/toss/confirm")
    public TossService.OrderView confirm(@CurrentUser long userId, @Valid @RequestBody ConfirmRequest in) {
        return toss.confirm(userId, in.paymentKey(), in.orderId(), in.amount());
    }

    @GetMapping("/api/payments/toss/orders")
    public TossService.OrderView latest(@CurrentUser long userId, @RequestParam long exchangeId) {
        return toss.latestFor(userId, exchangeId);
    }

    @PostMapping("/api/payments/toss/webhook")
    public Map<String, Object> webhook(@RequestBody Map<String, Object> body) {
        toss.webhook(body);
        return Map.of("ok", true);
    }
}
