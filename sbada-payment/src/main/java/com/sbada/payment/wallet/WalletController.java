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
 *  2026-11-25 금융 백엔드
 *  POST   /api/wallet/accounts/verify               1원 인증 시작(계좌로 1원 · 입금자명 숫자 4자리)
 *  POST   /api/wallet/accounts/verify/{id}/confirm  숫자 4자리 확인 → 계좌 등록(201)
 *  POST   /api/payments/escrow                      교환 추가 금액 안전결제(보관)
 *  GET    /api/payments/escrow?exchangeId=          그 교환의 안전결제 상태(당사자만)
 *  POST   /api/wallet/sandbox/reset                 [테스트] 내 지갑 잔액을 시연 기본값으로
 *  (정산 · 환불은 메인 서버만 부르는 내부 API: EscrowInternalController)
 *
 * 앱은 메인 서버(8097)로만 요청한다. 메인 서버의 게이트웨이가 이 주소들을 결제 서비스(8098)로 넘긴다.
 */
@RestController
public class WalletController {

    private final WalletService service;
    private final EscrowService escrow;

    public WalletController(WalletService service, EscrowService escrow) {
        this.service = service;
        this.escrow = escrow;
    }

    @GetMapping("/api/payment/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "sbada-payment", "demo", true,
                "mode", service.isSandbox() ? "SANDBOX" : "DEMO", "realMoney", false,
                "label", "시연 모드 · 가상 거래"); // 앱 결제 화면 상단 배지에 그대로 쓴다
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

    @PostMapping("/api/wallet/accounts/verify")
    public WalletDtos.VerifyStart startVerification(@CurrentUser long userId,
                                                    @Valid @RequestBody WalletDtos.AddAccountRequest in) {
        return service.startVerification(userId, in);
    }

    @PostMapping("/api/wallet/accounts/verify/{id}/confirm")
    public ResponseEntity<WalletDtos.AccountView> confirmVerification(@CurrentUser long userId, @PathVariable String id,
                                                                      @Valid @RequestBody WalletDtos.VerifyConfirm in) {
        return ResponseEntity.status(201).body(service.confirmVerification(userId, id, in.code()));
    }

    @PostMapping("/api/wallet/sandbox/reset")
    public WalletDtos.TxResult sandboxReset(@CurrentUser long userId) {
        return service.sandboxReset(userId);
    }

    @PostMapping("/api/payments/escrow")
    public WalletDtos.EscrowResult hold(@CurrentUser long userId, @Valid @RequestBody WalletDtos.EscrowRequest in) {
        return escrow.hold(userId, in);
    }

    @GetMapping("/api/payments/escrow")
    public WalletDtos.EscrowView escrow(@CurrentUser long userId, @RequestParam long exchangeId) {
        return escrow.view(userId, exchangeId);
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
