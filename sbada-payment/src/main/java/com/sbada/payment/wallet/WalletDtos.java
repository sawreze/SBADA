package com.sbada.payment.wallet;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 결제 서비스 요청 · 응답 모양 */
public final class WalletDtos {

    private WalletDtos() {
    }

    /** POST /api/wallet/accounts — 계좌번호는 숫자 · 하이픈(10~16자리 숫자) */
    public record AddAccountRequest(
            @NotBlank(message = "은행을 골라 주세요.") String bankCode,
            @NotBlank(message = "계좌번호를 입력해 주세요.") @Size(max = 30, message = "계좌번호가 너무 깁니다.") String accountNumber,
            @NotBlank(message = "예금주를 입력해 주세요.") @Size(max = 30, message = "예금주는 30자 이하입니다.") String holder) {
    }

    /** POST /api/wallet/withdraw */
    public record WithdrawRequest(
            @NotNull(message = "출금 계좌를 골라 주세요.") Long accountId,
            @NotNull(message = "금액을 입력해 주세요.") Long amount) {
    }

    /** POST /api/wallet/charge — 시연용 충전(결제창 흉내) */
    public record ChargeRequest(
            @NotBlank(message = "결제수단을 골라 주세요.") String method,
            @NotNull(message = "금액을 입력해 주세요.") Long amount) {
    }

    /** POST /api/payments/extra — 교환할 때 상대가 요구한 추가 요금 내기(수수료 5% 는 내는 사람 부담) */
    public record PayRequest(
            @NotNull(message = "받는 사람이 없습니다.") Long toUserId,
            @NotNull(message = "금액을 입력해 주세요.") Long amount,
            @NotBlank(message = "결제수단을 골라 주세요.") String method,
            Long exchangeId,
            @Size(max = 60, message = "메모는 60자 이하입니다.") String memo) {
    }

    public record AccountView(long id, String bankCode, String bankName, String accountMasked, String holder) {
        static AccountView from(WalletAccount a) {
            return new AccountView(a.getId(), a.getBankCode(), Catalog.BANKS.getOrDefault(a.getBankCode(), a.getBankCode()),
                    a.getAccountMasked(), a.getHolder());
        }
    }

    public record TxView(long id, String type, String typeLabel, long amount, long fee, long balanceAfter,
                         String method, String methodLabel, String memo, String createdAt) {
        static TxView from(WalletTransaction t) {
            return new TxView(t.getId(), t.getType(), TxType.labelOf(t.getType()), t.getAmount(), t.getFee(),
                    t.getBalanceAfter(), t.getMethod(), Catalog.methodLabel(t.getMethod()),
                    t.getMemo(), t.getCreatedAt().toString());
        }
    }

    /** GET /api/wallet 응답: 화면 하나를 그리는 데 필요한 것 전부 */
    public record WalletView(boolean demo, long balance, int feePercent, long minAmount, long maxAmount,
                             List<AccountView> accounts, List<TxView> transactions,
                             List<Catalog.Option> banks, List<Catalog.Option> methods) {
    }

    /** 결제 · 출금 · 충전 결과: 알림 문구 + 바뀐 잔액 */
    public record TxResult(String message, long balance, TxView transaction) {
    }

    /** GET /api/payments/quote?amount= — 추가 요금 결제 전에 수수료 · 총액 미리보기 */
    public record Quote(long amount, int feePercent, long fee, long total) {
    }

    // ───────────── 2026-11-25 안전결제(에스크로) · 1원 인증 · 샌드박스 ─────────────

    /**
     * POST /api/payments/escrow — 교환 추가 금액을 안전결제로 낸다(보관 → 교환 완료 때 정산).
     * toUserId 는 메인 서버 확인을 끈 테스트에서만 쓴다(평소에는 교환 정보에서 받는 사람을 정한다).
     */
    public record EscrowRequest(
            @NotNull(message = "교환 번호가 필요합니다.") Long exchangeId,
            @NotNull(message = "금액을 입력해 주세요.") Long amount,
            @NotBlank(message = "결제수단을 골라 주세요.") String method,
            Long toUserId,
            @Size(max = 60, message = "메모는 60자 이하입니다.") String memo) {
    }

    /** role: 보는 사람 입장 PAYER(낸 사람) · PAYEE(받을 사람), 내부 호출이면 null */
    public record EscrowView(long id, long exchangeId, String role, long amount, long fee, long total,
                             String method, String methodLabel, String status, String statusLabel,
                             String releaseAfter, String settledAt, String createdAt) {
        static EscrowView from(EscrowPayment e, Long viewerId) {
            String role = viewerId == null ? null
                    : viewerId.equals(e.getPayerId()) ? "PAYER" : viewerId.equals(e.getPayeeId()) ? "PAYEE" : null;
            String label = switch (e.getStatus()) {
                case EscrowPayment.HELD -> "보관 중";
                case EscrowPayment.RELEASED -> "정산 완료";
                case EscrowPayment.REFUNDED -> "환불 완료";
                default -> e.getStatus();
            };
            return new EscrowView(e.getId(), e.getExchangeId(), role, e.getAmount(), e.getFee(), e.total(),
                    e.getMethod(), Catalog.methodLabel(e.getMethod()), e.getStatus(), label,
                    e.getReleaseAfter().toString(), e.getSettledAt() == null ? null : e.getSettledAt().toString(),
                    e.getCreatedAt().toString());
        }
    }

    /** 안전결제 결과: 알림 문구 + 내 지갑 잔액 + 보관 정보 + 원장 한 줄 */
    public record EscrowResult(String message, long balance, EscrowView escrow, TxView transaction) {
    }

    /** POST /internal/escrow/release · refund (메인 서버만 부름) */
    public record EscrowCommand(@NotNull(message = "교환 번호가 필요합니다.") Long exchangeId,
                                @Size(max = 60) String reason) {
    }

    /**
     * POST /api/wallet/accounts/verify 응답 — 1원 인증 시작.
     * demoMemo: 샌드박스에서만 채워진다(실제 입금이 없으니 입금자명을 대신 보여 줌). 알림으로도 같은 내용이 간다.
     */
    public record VerifyStart(String verificationId, String bankName, String accountMasked, String holder,
                              String expiresAt, int maxAttempts, String demoMemo, String guide) {
    }

    /** POST /api/wallet/accounts/verify/{id}/confirm — 입금자명의 숫자 4자리 */
    public record VerifyConfirm(
            @NotBlank(message = "인증 숫자를 입력해 주세요.")
            @Pattern(regexp = "\\s*\\d{4}\\s*", message = "숫자 4자리를 입력해 주세요.") String code) {
    }
}
