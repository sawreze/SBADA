package com.sbada.payment.wallet;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.payment.auth.CurrentUser;

import jakarta.validation.Valid;

/**
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 결제 서비스 API (시연용 — 실제 돈은 움직이지 않음, 🔒 로그인 필요)   │
 * └──────────────────────────────────────────────────────────────┘
 *  GET    /api/wallet                    잔액 · 계좌 · 최근 거래 · 은행/결제수단 목록
 *  POST   /api/wallet/accounts           출금 계좌 등록(계좌번호는 RSA+AES 로 암호화 저장)
 *  DELETE /api/wallet/accounts/{id}      계좌 삭제
 *  POST   /api/wallet/withdraw           출금 → "출금되었습니다" 알림
 *  POST   /api/wallet/charge             충전(카드 · 카카오페이 · 토스페이 · 계좌이체 흉내)
 *  GET    /api/payments/quote?amount=    추가 요금 수수료(5%) 미리보기
 *  POST   /api/payments/extra            교환 추가 요금 결제 → 받는 사람 지갑에 입금
 *  GET    /api/payment/health            상태 확인(로그인 불필요)
 *
 * 앱은 메인 서버(8097)로만 요청한다. 메인 서버의 게이트웨이가 이 주소들을 결제 서비스(8098)로 넘긴다.
 */
@RestController
public class WalletController {

    private final WalletService service;

    public WalletController(WalletService service) {
        this.service = service;
    }

    @GetMapping("/api/payment/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "sbada-payment", "demo", true);
    }

    @GetMapping("/api/wallet")
    public WalletDtos.WalletView wallet(@CurrentUser long userId) {
        return service.wallet(userId);
    }

    @PostMapping("/api/wallet/accounts")
    public ResponseEntity<WalletDtos.AccountView> addAccount(@CurrentUser long userId,
                                                             @Valid @RequestBody WalletDtos.AddAccountRequest in) {
        return ResponseEntity.status(201).body(service.addAccount(userId, in));
    }

    @DeleteMapping("/api/wallet/accounts/{id}")
    public ResponseEntity<Void> deleteAccount(@CurrentUser long userId, @PathVariable long id) {
        service.deleteAccount(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/wallet/withdraw")
    public WalletDtos.TxResult withdraw(@CurrentUser long userId, @Valid @RequestBody WalletDtos.WithdrawRequest in) {
        return service.withdraw(userId, in);
    }

    @PostMapping("/api/wallet/charge")
    public WalletDtos.TxResult charge(@CurrentUser long userId, @Valid @RequestBody WalletDtos.ChargeRequest in) {
        return service.charge(userId, in);
    }

    @GetMapping("/api/payments/quote")
    public WalletDtos.Quote quote(@RequestParam long amount) {
        return service.quote(amount);
    }

    @PostMapping("/api/payments/extra")
    public WalletDtos.TxResult pay(@CurrentUser long userId, @Valid @RequestBody WalletDtos.PayRequest in) {
        return service.pay(userId, in);
    }
}
