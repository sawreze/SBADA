package com.sbada.payment.wallet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
 *  - 2026-11-25: 1원 계좌 인증 · 샌드박스(테스트 잔액 초기화) 추가. 안전결제는 EscrowService 가 이 클래스의 잠금 · 원장 도우미를 쓴다.
 *
 * [샌드박스 정책] 실제 돈은 한 푼도 움직이지 않는다(실제 PG · 은행 API 호출 코드가 없음).
 *  그래서 전자금융업 등록 · PG 계약 같은 규제 없이 '돈이 오가는 흐름'을 끝까지 시험할 수 있다.
 *  잔액 · 결제 · 정산 · 환불은 전부 이 서비스 DB 안의 숫자다(sbada.payment.sandbox=true 가 기본).
 */
@Service
public class WalletService {

    /** 1원 인증: 5분 안에 5번까지, 10분에 5번까지 시작할 수 있다 */
    static final Duration VERIFY_TTL = Duration.ofMinutes(5);
    static final int VERIFY_MAX_ATTEMPTS = 5;
    static final int VERIFY_MAX_STARTS = 5;

    private final WalletAccountRepository accounts;
    private final AccountVerificationRepository verifications;
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
    private final boolean sandbox;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public WalletService(WalletAccountRepository accounts, AccountVerificationRepository verifications,
                         WalletTransactionRepository txs, OutboxRepository outbox,
                         AccountCipher cipher, CoreClient core, TransactionTemplate tx,
                         @Value("${sbada.wallet.demo-initial-balance}") long initialBalance,
                         @Value("${sbada.wallet.fee-percent}") int feePercent,
                         @Value("${sbada.wallet.min-amount}") long minAmount,
                         @Value("${sbada.wallet.max-amount}") long maxAmount,
                         @Value("${sbada.wallet.max-accounts}") int maxAccounts,
                         @Value("${sbada.payment.sandbox:true}") boolean sandbox) {
        this.accounts = accounts;
        this.verifications = verifications;
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
        this.sandbox = sandbox;
    }

    public boolean isSandbox() {
        return sandbox;
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
        NewAccount a = normalize(in);
        return locked(userId, () -> {
            String hash = cipher.fingerprint(a.digits());
            checkCanAdd(userId, hash);
            AccountCipher.Sealed sealed = cipher.seal(a.digits());
            WalletAccount saved = accounts.save(new WalletAccount(userId, a.bank(), a.masked(), a.holder(), hash,
                    sealed.encAccount(), sealed.encKey()));
            return WalletDtos.AccountView.from(saved);
        });
    }

    // ───────────── 1원 계좌 인증 (2026-11-25) ─────────────

    /**
     * ① 인증 시작: 계좌로 1원을 보낸다(시연: 실제 입금 없음). 입금자명 '쓰바다 1234' 의 숫자 4자리가 인증 숫자.
     *    숫자는 알림(이벤트)으로도 보내고, 샌드박스면 응답에도 demoMemo 로 넣어 준다.
     *    계좌번호는 이 시점에 이미 암호화해서만 보관한다. 숫자 4자리는 HMAC 값만 저장한다.
     */
    public WalletDtos.VerifyStart startVerification(long userId, WalletDtos.AddAccountRequest in) {
        NewAccount a = normalize(in);
        return locked(userId, () -> {
            if (verifications.countByUserIdAndCreatedAtAfter(userId, Instant.now().minus(Duration.ofMinutes(10)))
                    >= VERIFY_MAX_STARTS) {
                throw new ApiException(429, "TOO_MANY_VERIFICATIONS", "인증을 너무 많이 시도했어요. 10분 뒤에 다시 해 주세요.");
            }
            String hash = cipher.fingerprint(a.digits());
            checkCanAdd(userId, hash);
            String id = UUID.randomUUID().toString();
            String code = String.format("%04d", random.nextInt(10_000));
            AccountCipher.Sealed sealed = cipher.seal(a.digits());
            Instant expires = Instant.now().plus(VERIFY_TTL);
            verifications.save(new AccountVerification(id, userId, a.bank(), a.masked(), a.holder(), hash,
                    sealed.encAccount(), sealed.encKey(), codeHash(id, code), expires));
            String bankName = Catalog.BANKS.get(a.bank());
            String memo = "쓰바다 " + code;
            // 실제 서비스라면 여기서 펌뱅킹 · 오픈뱅킹 API 로 1원을 보낸다(입금자명 = memo). 시연이라 보내지 않는다
            event("ACCOUNT_VERIFY_SENT", userId, "[1원 입금] " + bankName + " " + a.masked(),
                    "입금자명 '" + memo + "' 의 숫자 4자리를 입력해 주세요. 5분 안에 입력해야 해요. (시연 · 실제 입금 없음)", 0);
            return new WalletDtos.VerifyStart(id, bankName, a.masked(), a.holder(), expires.toString(),
                    VERIFY_MAX_ATTEMPTS, sandbox ? memo : null,
                    bankName + " " + a.masked() + " 계좌로 1원을 보냈어요. 입금자명의 숫자 4자리를 입력해 주세요.");
        });
    }

    /** ② 인증 확인: 숫자 4자리가 맞으면 계좌 등록(201). 틀리면 남은 기회를 알려 준다 */
    public WalletDtos.AccountView confirmVerification(long userId, String verificationId, String code) {
        String given = code == null ? "" : code.trim();
        VerifyOutcome out = locked(userId, () -> {
            AccountVerification v = verifications.findById(verificationId)
                    .filter(x -> x.getUserId() == userId)
                    .orElseThrow(() -> ApiException.notFound("VERIFICATION_NOT_FOUND", "인증 요청을 찾을 수 없어요. 처음부터 다시 해 주세요."));
            if (v.isVerified()) {
                throw ApiException.conflict("ALREADY_VERIFIED", "이미 인증을 마친 계좌예요.");
            }
            if (v.isExpired()) {
                throw ApiException.conflict("VERIFICATION_EXPIRED", "인증 시간(5분)이 지났어요. 1원을 다시 보내 주세요.");
            }
            if (v.getAttempts() >= VERIFY_MAX_ATTEMPTS) {
                throw ApiException.conflict("TOO_MANY_ATTEMPTS", "5번 틀려서 이 인증은 끝났어요. 1원을 다시 보내 주세요.");
            }
            boolean ok = MessageDigest.isEqual(v.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                    codeHash(verificationId, given).getBytes(StandardCharsets.US_ASCII));
            if (!ok) {
                v.failAttempt(); // 틀린 횟수는 저장해야 하므로 예외를 던지지 않고 커밋한 뒤 밖에서 알린다
                return new VerifyOutcome(null, VERIFY_MAX_ATTEMPTS - v.getAttempts());
            }
            checkCanAdd(userId, v.getAccountHash());
            v.markVerified();
            WalletAccount saved = accounts.save(new WalletAccount(userId, v.getBankCode(), v.getAccountMasked(),
                    v.getHolder(), v.getAccountHash(), v.getEncAccount(), v.getEncKey()));
            return new VerifyOutcome(WalletDtos.AccountView.from(saved), 0);
        });
        if (out.account() == null) {
            throw ApiException.badRequest("WRONG_CODE", out.remaining() > 0
                    ? "인증 숫자가 맞지 않아요. (남은 기회 " + out.remaining() + "번)"
                    : "5번 틀려서 이 인증은 끝났어요. 1원을 다시 보내 주세요.");
        }
        return out.account();
    }

    private record VerifyOutcome(WalletDtos.AccountView account, int remaining) {
    }

    private record NewAccount(String bank, String digits, String holder, String masked) {
    }

    private NewAccount normalize(WalletDtos.AddAccountRequest in) {
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
        return new NewAccount(bank, digits, holder, "****" + digits.substring(digits.length() - 4));
    }

    private void checkCanAdd(long userId, String accountHash) {
        if (accounts.countByUserId(userId) >= maxAccounts) {
            throw ApiException.conflict("TOO_MANY_ACCOUNTS", "계좌는 " + maxAccounts + "개까지 등록할 수 있습니다.");
        }
        if (accounts.existsByUserIdAndAccountHash(userId, accountHash)) {
            throw ApiException.conflict("DUPLICATE_ACCOUNT", "이미 등록한 계좌입니다.");
        }
    }

    /** 인증 숫자 HMAC: 인증 번호와 묶어서 같은 숫자라도 건마다 다른 값이 되게 한다 */
    private String codeHash(String verificationId, String code) {
        return cipher.fingerprint("verify:" + verificationId + ":" + code);
    }

    // ───────────── 샌드박스(테스트용) ─────────────

    /** 내 지갑 잔액을 시연 기본값(10만 원)으로 되돌린다. 샌드박스가 꺼져 있으면 404 */
    public WalletDtos.TxResult sandboxReset(long userId) {
        if (!sandbox) {
            throw ApiException.notFound("SANDBOX_DISABLED", "테스트 기능이 꺼져 있어요.");
        }
        return locked(userId, () -> {
            ensureWallet(userId);
            long delta = initialBalance - balance(userId);
            WalletTransaction t = txs.save(new WalletTransaction(userId, TxType.SANDBOX_RESET, delta, 0,
                    initialBalance, null, null, null, "테스트 잔액 초기화"));
            return new WalletDtos.TxResult("테스트 잔액을 " + won(initialBalance) + "으로 맞췄어요", initialBalance,
                    WalletDtos.TxView.from(t));
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

    void ensureWallet(long userId) {
        if (!txs.existsByUserId(userId)) {
            txs.save(new WalletTransaction(userId, TxType.DEMO_CHARGE, initialBalance, 0, initialBalance, null, null,
                    null, "시연용 기본 잔액"));
        }
    }

    long balance(long userId) {
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

    /** 안전결제 결제수단: 충전 수단(카드 · 간편결제 · 계좌이체) + 쓰바다 지갑 잔액(WALLET) */
    String payMethod(String code) {
        String m = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return Catalog.WALLET.equals(m) ? m : method(m);
    }

    void checkAmount(long amount) {
        if (amount < minAmount || amount > maxAmount) {
            throw ApiException.badRequest("INVALID_AMOUNT",
                    "금액은 " + won(minAmount) + " 이상 " + won(maxAmount) + " 이하로 입력해 주세요.");
        }
    }

    /** 수수료: 금액 × 5%, 원 단위 반올림 */
    long fee(long amount) {
        return Math.round(amount * feePercent / 100.0);
    }

    void event(String type, long userId, String title, String body, long refId) {
        event(type, userId, title, body, refId, Map.of());
    }

    /** extra: 메인 서버가 함께 써야 하는 값(예: exchangeId → 교환의 결제 상태를 바꾼다) */
    void event(String type, long userId, String title, String body, long refId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("title", title);
        payload.put("body", body);
        payload.put("refId", refId);
        outbox.save(new OutboxEvent(type, userId, Json.write(payload)));
    }

    /** 사용자별 잠금 → 그 안에서 트랜잭션. 커밋이 끝난 뒤에 잠금을 푼다 */
    <T> T locked(long userId, Supplier<T> work) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            return tx.execute(status -> work.get());
        }
    }

    /** 두 사람 잠금: 항상 번호가 작은 쪽부터 잡아 교착(deadlock)을 막는다 */
    <T> T lockedBoth(long a, long b, Supplier<T> work) {
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
