package com.sbada.payment.wallet;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.payment.common.ApiException;
import com.sbada.payment.common.Json;
import com.sbada.payment.crypto.AccountCipher;
import com.sbada.payment.events.CoreClient;
import com.sbada.payment.events.OutboxEvent;
import com.sbada.payment.events.OutboxRepository;

/**
 * 지갑 · 결제 업무 규칙 (시연용: 실제 PG · 은행 호출 없음)
 *
 *  - 잔액은 원장(wallet_transactions)의 마지막 balanceAfter. 줄을 고치지 않고 새 줄만 더한다.
 *  - 같은 사람의 돈이 동시에 두 번 빠지지 않도록 사용자별 잠금 안에서 트랜잭션을 연다.
 *  - 알림은 직접 보내지 않고 아웃박스 이벤트로 남긴다 → OutboxRelay 가 메인 서버(알림 서비스)로 전달.
 */
@Service
public class WalletService {

    private final WalletAccountRepository accounts;
    private final WalletTransactionRepository txs;
    private final OutboxRepository outbox;
    private final AccountCipher cipher;
    private final CoreClient core;
    private final TransactionTemplate tx;
    private final long initialBalance;
    private final int feePercent;
    private final long minAmount;
    private final long maxAmount;
    private final int maxAccounts;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public WalletService(WalletAccountRepository accounts, WalletTransactionRepository txs, OutboxRepository outbox,
                         AccountCipher cipher, CoreClient core, TransactionTemplate tx,
                         @Value("${sbada.wallet.demo-initial-balance}") long initialBalance,
                         @Value("${sbada.wallet.fee-percent}") int feePercent,
                         @Value("${sbada.wallet.min-amount}") long minAmount,
                         @Value("${sbada.wallet.max-amount}") long maxAmount,
                         @Value("${sbada.wallet.max-accounts}") int maxAccounts) {
        this.accounts = accounts;
        this.txs = txs;
        this.outbox = outbox;
        this.cipher = cipher;
        this.core = core;
        this.tx = tx;
        this.initialBalance = initialBalance;
        this.feePercent = feePercent;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.maxAccounts = maxAccounts;
    }

    // ───────────── 조회 ─────────────

    public WalletDtos.WalletView wallet(long userId) {
        return locked(userId, () -> {
            ensureWallet(userId);
            return view(userId);
        });
    }

    public WalletDtos.Quote quote(long amount) {
        checkAmount(amount);
        long fee = fee(amount);
        return new WalletDtos.Quote(amount, feePercent, fee, amount + fee);
    }

    // ───────────── 계좌 ─────────────

    public WalletDtos.AccountView addAccount(long userId, WalletDtos.AddAccountRequest in) {
        String bank = in.bankCode().trim().toUpperCase(Locale.ROOT);
        if (!Catalog.BANKS.containsKey(bank)) {
            throw ApiException.badRequest("INVALID_BANK", "지원하지 않는 은행입니다.");
        }
        String digits = in.accountNumber().replaceAll("[\\s-]", "");
        if (!digits.matches("\\d{10,16}")) {
            throw ApiException.badRequest("INVALID_ACCOUNT_NUMBER", "계좌번호는 숫자 10~16자리로 입력해 주세요.");
        }
        String holder = in.holder().trim();
        if (holder.isEmpty()) {
            throw ApiException.badRequest("INVALID_HOLDER", "예금주를 입력해 주세요.");
        }
        return locked(userId, () -> {
            if (accounts.countByUserId(userId) >= maxAccounts) {
                throw ApiException.conflict("TOO_MANY_ACCOUNTS", "계좌는 " + maxAccounts + "개까지 등록할 수 있습니다.");
            }
            String hash = cipher.fingerprint(digits);
            if (accounts.existsByUserIdAndAccountHash(userId, hash)) {
                throw ApiException.conflict("DUPLICATE_ACCOUNT", "이미 등록한 계좌입니다.");
            }
            AccountCipher.Sealed sealed = cipher.seal(digits);
            String masked = "****" + digits.substring(digits.length() - 4);
            WalletAccount saved = accounts.save(
                    new WalletAccount(userId, bank, masked, holder, hash, sealed.encAccount(), sealed.encKey()));
            return WalletDtos.AccountView.from(saved);
        });
    }

    public void deleteAccount(long userId, long accountId) {
        locked(userId, () -> {
            accounts.delete(account(userId, accountId));
            return null;
        });
    }

    // ───────────── 출금 · 충전 · 추가 요금 ─────────────

    /** 지갑 → 내 계좌 출금(시연: 실제 송금 없음). 성공하면 '출금되었습니다' 알림 이벤트 */
    public WalletDtos.TxResult withdraw(long userId, WalletDtos.WithdrawRequest in) {
        long amount = in.amount();
        checkAmount(amount);
        return locked(userId, () -> {
            ensureWallet(userId);
            WalletAccount acc = account(userId, in.accountId());
            long balance = balance(userId);
            if (balance < amount) {
                throw ApiException.conflict("INSUFFICIENT_BALANCE",
                        "잔액이 부족합니다. (잔액 " + won(balance) + ")");
            }
            // 실제 서비스라면 여기서 cipher.open(...) 으로 계좌번호를 복원해 PG 지급대행 API 로 보낸다
            String bankName = Catalog.BANKS.getOrDefault(acc.getBankCode(), acc.getBankCode());
            WalletTransaction t = txs.save(new WalletTransaction(userId, TxType.WITHDRAW, -amount, 0, balance - amount,
                    null, acc.getId(), null, bankName + " " + acc.getAccountMasked()));
            String body = bankName + " " + acc.getAccountMasked() + " 계좌로 " + won(amount) + "을 보냈어요. (시연 · 실제 송금 없음)";
            event("WITHDRAWN", userId, "출금되었습니다", body, t.getId());
            return new WalletDtos.TxResult("출금되었습니다", t.getBalanceAfter(), WalletDtos.TxView.from(t));
        });
    }

    /** 결제창 흉내: 카드 · 카카오페이 · 토스페이 · 계좌이체로 지갑 충전 */
    public WalletDtos.TxResult charge(long userId, WalletDtos.ChargeRequest in) {
        long amount = in.amount();
        checkAmount(amount);
        String method = method(in.method());
        return locked(userId, () -> {
            ensureWallet(userId);
            long balance = balance(userId) + amount;
            WalletTransaction t = txs.save(new WalletTransaction(userId, TxType.CHARGE, amount, 0, balance, method,
                    null, null, Catalog.METHODS.get(method) + " 충전"));
            event("CHARGED", userId, "충전되었습니다", Catalog.METHODS.get(method) + "로 " + won(amount) + " 충전 (시연)", t.getId());
            return new WalletDtos.TxResult("충전되었습니다", balance, WalletDtos.TxView.from(t));
        });
    }

    /**
     * 교환 추가 요금: 보내는 사람이 (금액 + 수수료 5%)를 결제수단으로 내고,
     * 앱이 수수료를 뺀 금액을 받는 사람 지갑에 넣는다. 받는 사람은 나중에 출금한다.
     */
    public WalletDtos.TxResult pay(long userId, WalletDtos.PayRequest in) {
        long to = in.toUserId();
        if (to == userId) {
            throw ApiException.badRequest("SELF_PAY", "나에게는 보낼 수 없습니다.");
        }
        long amount = in.amount();
        checkAmount(amount);
        String method = method(in.method());
        core.requireUser(to); // 회원 서비스에 '이 사람 있나요?' (잠금 밖에서 — 네트워크 호출)
        long fee = fee(amount);
        String memo = in.memo() == null || in.memo().isBlank() ? "교환 추가 요금" : in.memo().trim();
        return lockedBoth(userId, to, () -> {
            ensureWallet(userId);
            ensureWallet(to);
            long payerBalance = balance(userId); // 카드 · 간편결제로 냈으니 지갑 잔액은 그대로
            WalletTransaction paid = txs.save(new WalletTransaction(userId, TxType.PAY, -(amount + fee), fee,
                    payerBalance, method, null, to, memo));
            long receiverBalance = balance(to) + amount;
            WalletTransaction received = txs.save(new WalletTransaction(to, TxType.RECEIVE, amount, 0,
                    receiverBalance, null, null, userId, memo));
            event("PAYMENT_SENT", userId, "결제되었습니다",
                    Catalog.METHODS.get(method) + "로 " + won(amount + fee) + " 결제 (수수료 " + won(fee) + " 포함 · 시연)",
                    paid.getId());
            event("PAYMENT_RECEIVED", to, "추가 요금이 들어왔어요",
                    won(amount) + "이 지갑에 들어왔어요. 지갑에서 출금할 수 있어요. (시연)", received.getId());
            return new WalletDtos.TxResult("결제되었습니다", payerBalance, WalletDtos.TxView.from(paid));
        });
    }

    // ───────────── 내부 도우미 ─────────────

    private void ensureWallet(long userId) {
        if (!txs.existsByUserId(userId)) {
            txs.save(new WalletTransaction(userId, TxType.DEMO_CHARGE, initialBalance, 0, initialBalance, null, null,
                    null, "시연용 기본 잔액"));
        }
    }

    private long balance(long userId) {
        return txs.findFirstByUserIdOrderByIdDesc(userId).map(WalletTransaction::getBalanceAfter).orElse(0L);
    }

    private WalletDtos.WalletView view(long userId) {
        List<WalletDtos.AccountView> accs = accounts.findByUserIdOrderByIdAsc(userId).stream()
                .map(WalletDtos.AccountView::from).toList();
        List<WalletDtos.TxView> recent = txs.findTop30ByUserIdOrderByIdDesc(userId).stream()
                .map(WalletDtos.TxView::from).toList();
        return new WalletDtos.WalletView(true, balance(userId), feePercent, minAmount, maxAmount, accs, recent,
                Catalog.options(Catalog.BANKS), Catalog.options(Catalog.METHODS));
    }

    private WalletAccount account(long userId, Long accountId) {
        if (accountId == null) {
            throw ApiException.badRequest("ACCOUNT_REQUIRED", "출금 계좌를 골라 주세요.");
        }
        return accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
    }

    private String method(String code) {
        String m = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!Catalog.METHODS.containsKey(m)) {
            throw ApiException.badRequest("INVALID_METHOD", "지원하지 않는 결제수단입니다.");
        }
        return m;
    }

    private void checkAmount(long amount) {
        if (amount < minAmount || amount > maxAmount) {
            throw ApiException.badRequest("INVALID_AMOUNT",
                    "금액은 " + won(minAmount) + " 이상 " + won(maxAmount) + " 이하로 입력해 주세요.");
        }
    }

    /** 수수료: 금액 × 5%, 원 단위 반올림 */
    long fee(long amount) {
        return Math.round(amount * feePercent / 100.0);
    }

    private void event(String type, long userId, String title, String body, long refId) {
        outbox.save(new OutboxEvent(type, userId, Json.write(Map.of("title", title, "body", body, "refId", refId))));
    }

    /** 사용자별 잠금 → 그 안에서 트랜잭션. 커밋이 끝난 뒤에 잠금을 푼다 */
    private <T> T locked(long userId, Supplier<T> work) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            return tx.execute(status -> work.get());
        }
    }

    /** 두 사람 잠금: 항상 번호가 작은 쪽부터 잡아 교착(deadlock)을 막는다 */
    private <T> T lockedBoth(long a, long b, Supplier<T> work) {
        Object first = locks.computeIfAbsent(Math.min(a, b), k -> new Object());
        Object second = locks.computeIfAbsent(Math.max(a, b), k -> new Object());
        synchronized (first) {
            synchronized (second) {
                return tx.execute(status -> work.get());
            }
        }
    }

    static String won(long amount) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(amount) + "원";
    }
}
