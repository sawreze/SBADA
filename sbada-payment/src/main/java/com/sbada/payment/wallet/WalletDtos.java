package com.sbada.payment.wallet;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
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
                    t.getBalanceAfter(), t.getMethod(),
                    t.getMethod() == null ? null : Catalog.METHODS.getOrDefault(t.getMethod(), t.getMethod()),
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
}
