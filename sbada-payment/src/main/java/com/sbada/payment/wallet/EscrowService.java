package com.sbada.payment.wallet;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.sbada.payment.common.ApiException;
import com.sbada.payment.events.CoreClient;

/**
 * 안전결제(에스크로) — 교환 추가 금액 (2026-11-25, 시연용 샌드박스: 실제 돈은 움직이지 않음)
 *
 *   ① hold    낸 사람이 결제 → 돈을 바로 주지 않고 '보관(HELD)'. 금액 + 수수료 5%(낸 사람 부담)
 *   ② release 교환을 마치면(낸 사람 확정 · 메인 서버 호출) → 받는 사람 지갑에 금액만큼 입금(RELEASED)
 *   ③ refund  교환이 거절 · 취소되면 → 낸 사람에게 금액 + 수수료 전부 돌려줌(REFUNDED)
 *   ④ sweep   1분마다 보관 중인 건을 메인 서버와 맞춰 본다(대사 reconcile)
 *             - 교환이 깨졌는데 환불 호출이 유실됐으면 → 환불
 *             - 교환이 성사되고 자동 확정 시간(기본 72시간)이 지났으면 → 정산
 *
 * 누가 얼마를 내는지는 교환 서비스(메인 서버)가 정한다. 결제 서비스는 /internal/exchanges/{id} 로 확인만 하고,
 * 알림은 아웃박스 이벤트(ESCROW_HELD · ESCROW_RELEASED · ESCROW_REFUNDED, exchangeId 포함)로 넘긴다.
 * 같은 사람 돈이 동시에 두 번 움직이지 않도록 WalletService 의 사용자별 잠금을 그대로 쓴다.
 */
@Service
public class EscrowService {

    private static final Logger log = LoggerFactory.getLogger(EscrowService.class);
    private static final List<String> PAID = List.of(EscrowPayment.HELD, EscrowPayment.RELEASED);

    private final WalletService wallet;
    private final EscrowPaymentRepository escrows;
    private final WalletTransactionRepository txs;
    private final CoreClient core;
    private final Duration autoRelease;
    private final org.springframework.context.ApplicationEventPublisher events;

    /** 토스로 결제된 보관금을 환불할 때: TossService 가 받아 토스 결제를 취소한다 */
    public record TossRefundNeeded(long exchangeId, String reason) {
    }

    public EscrowService(WalletService wallet, EscrowPaymentRepository escrows, WalletTransactionRepository txs,
                         CoreClient core, org.springframework.context.ApplicationEventPublisher events,
                         @Value("${sbada.escrow.auto-release-minutes:43200}") long autoReleaseMinutes) {
        this.events = events;
        this.wallet = wallet;
        this.escrows = escrows;
        this.txs = txs;
        this.core = core;
        this.autoRelease = Duration.ofMinutes(autoReleaseMinutes);
    }

    // ───────────── ① 결제(보관) ─────────────

    public WalletDtos.EscrowResult hold(long userId, WalletDtos.EscrowRequest in) {
        return hold(userId, in, null);
    }

    /**
     * 2026-11-25 토스페이먼츠 승인 · 가상계좌 입금이 확인된 뒤 보관(TossService 만 부른다).
     * method 는 TOSS_CARD · TOSS_VA — 앱이 보낸 값이 아니라 결제 서버가 토스에 확인한 값이다.
     */
    public WalletDtos.EscrowResult holdVerifiedExternal(long payerId, long exchangeId, long amount, long payeeId,
                                                        String method, String memo) {
        if (!Catalog.TOSS_CARD.equals(method) && !Catalog.TOSS_VA.equals(method)) {
            throw new IllegalArgumentException("외부 결제 코드가 아니에요: " + method);
        }
        return hold(payerId, new WalletDtos.EscrowRequest(exchangeId, amount, method, payeeId, memo), method);
    }

    /** 결제 조건 확인(토스 주문을 만들 때): 낼 사람이 맞는지, 금액 · 받는 사람. 메인 서버 확인을 끈 테스트에서는 넘겨받은 값 */
    public record Terms(long payeeId, long amount) {
    }

    public Terms termsFor(long userId, long exchangeId, Long amount, Long toUserId) {
        if (!core.verifies()) {
            if (amount == null || toUserId == null) {
                throw ApiException.badRequest("PAYEE_REQUIRED", "받는 사람과 금액이 필요해요.");
            }
            wallet.checkAmount(amount);
            return new Terms(toUserId, amount);
        }
        CoreClient.ExchangeInfo x = core.findExchange(exchangeId)
                .orElseThrow(() -> ApiException.notFound("EXCHANGE_NOT_FOUND", "교환을 찾을 수 없습니다."));
        long payee = resolvePayee(userId, exchangeId, x.extraAmount(), null);
        return new Terms(payee, x.extraAmount());
    }

    public boolean alreadyPaid(long exchangeId) {
        return escrows.existsByExchangeIdAndStatusIn(exchangeId, PAID);
    }

    private WalletDtos.EscrowResult hold(long userId, WalletDtos.EscrowRequest in, String verifiedMethod) {
        long exchangeId = in.exchangeId();
        long amount = in.amount();
        wallet.checkAmount(amount);
        String method = verifiedMethod != null ? verifiedMethod : wallet.payMethod(in.method());
        long payee = verifiedMethod != null && !core.verifies() ? in.toUserId()
                : resolvePayee(userId, exchangeId, amount, in.toUserId()); // 네트워크 호출은 잠금 밖에서
        if (payee == userId) {
            throw ApiException.badRequest("SELF_PAY", "나에게는 보낼 수 없습니다.");
        }
        long fee = wallet.fee(amount);
        long total = amount + fee;
        String memo = in.memo() == null || in.memo().isBlank() ? "교환 추가 금액(안전결제)" : in.memo().trim();
        return wallet.lockedBoth(userId, payee, () -> {
            if (escrows.existsByExchangeIdAndStatusIn(exchangeId, PAID)) {
                throw ApiException.conflict("ALREADY_PAID", "이미 결제한 교환이에요.");
            }
            wallet.ensureWallet(userId);
            wallet.ensureWallet(payee);
            long before = wallet.balance(userId);
            long after = before;
            if (Catalog.WALLET.equals(method)) { // 지갑 잔액으로 내면 잔액이 줄고, 카드 · 간편결제면 잔액은 그대로
                if (before < total) {
                    throw ApiException.conflict("INSUFFICIENT_BALANCE",
                            "지갑 잔액이 부족합니다. (잔액 " + WalletService.won(before) + ", 필요 " + WalletService.won(total) + ")");
                }
                after = before - total;
            }
            WalletTransaction t = txs.save(new WalletTransaction(userId, TxType.ESCROW_HOLD, -total, fee, after,
                    method, null, payee, memo));
            EscrowPayment e = escrows.save(new EscrowPayment(exchangeId, userId, payee, amount, fee, method,
                    Instant.now().plus(autoRelease)));
            Map<String, Object> ref = Map.of("exchangeId", exchangeId);
            wallet.event("ESCROW_HELD", userId, "결제되었습니다",
                    Catalog.methodLabel(method) + "로 " + WalletService.won(total) + " 결제(수수료 " + WalletService.won(fee)
                            + " 포함). 교환을 마칠 때까지 안전하게 보관해요. (시연)", e.getId(), ref);
            wallet.event("ESCROW_HELD", payee, "추가 금액이 결제되었어요",
                    "상대가 " + WalletService.won(amount) + "을 안전결제로 냈어요. 교환을 마치면 지갑으로 들어와요. (시연)",
                    e.getId(), ref);
            return new WalletDtos.EscrowResult("결제되었습니다", after, WalletDtos.EscrowView.from(e, userId),
                    WalletDtos.TxView.from(t));
        });
    }

    /** 받는 사람 정하기: 평소에는 메인 서버의 교환 조건을 따르고, 확인을 끈 테스트에서는 toUserId 를 쓴다 */
    private long resolvePayee(long userId, long exchangeId, long amount, Long toUserId) {
        if (!core.verifies()) {
            if (toUserId == null) {
                throw ApiException.badRequest("PAYEE_REQUIRED", "받는 사람이 없습니다.");
            }
            return toUserId;
        }
        CoreClient.ExchangeInfo x = core.findExchange(exchangeId)
                .orElseThrow(() -> ApiException.notFound("EXCHANGE_NOT_FOUND", "교환을 찾을 수 없습니다."));
        if (!"REQUESTED".equals(x.status()) && !"ACCEPTED".equals(x.status())) {
            throw ApiException.conflict("EXCHANGE_CLOSED", "이미 끝난 교환이에요.");
        }
        if (x.payerId() == null || x.payeeId() == null || x.extraAmount() <= 0) {
            throw ApiException.conflict("NO_EXTRA_TERMS", "이 교환에는 추가 금액 조건이 없어요.");
        }
        if (x.payerId() != userId) {
            throw new ApiException(403, "NOT_PAYER", "이 교환의 추가 금액을 낼 사람이 아니에요.");
        }
        if (x.extraAmount() != amount) {
            throw ApiException.conflict("AMOUNT_MISMATCH",
                    "약속한 추가 금액(" + WalletService.won(x.extraAmount()) + ")과 달라요. 교환 화면을 새로 고쳐 주세요.");
        }
        return x.payeeId();
    }

    // ───────────── 조회 ─────────────

    /** 당사자(낸 사람 · 받을 사람)만 볼 수 있다. 아니면 없는 것처럼 404 */
    public WalletDtos.EscrowView view(long userId, long exchangeId) {
        return escrows.findFirstByExchangeIdOrderByIdDesc(exchangeId)
                .filter(e -> e.getPayerId() == userId || e.getPayeeId() == userId)
                .map(e -> WalletDtos.EscrowView.from(e, userId))
                .orElseThrow(() -> ApiException.notFound("ESCROW_NOT_FOUND", "이 교환의 결제 내역이 없어요."));
    }

    // ───────────── ② 정산 · ③ 환불 (메인 서버 내부 호출 · 자동 처리) ─────────────

    /** 받는 사람 지갑으로 정산. 이미 정산됐으면 그대로 돌려준다(같은 호출이 두 번 와도 안전 = 멱등) */
    public WalletDtos.EscrowView release(long exchangeId) {
        EscrowPayment found = latest(exchangeId);
        return wallet.lockedBoth(found.getPayerId(), found.getPayeeId(), () -> {
            EscrowPayment e = escrows.findById(found.getId()).orElseThrow();
            if (EscrowPayment.RELEASED.equals(e.getStatus())) {
                return WalletDtos.EscrowView.from(e, null);
            }
            if (!e.isHeld()) {
                throw ApiException.conflict("ALREADY_REFUNDED", "이미 환불된 결제예요.");
            }
            e.release();
            long payee = e.getPayeeId();
            wallet.ensureWallet(payee);
            long balance = wallet.balance(payee) + e.getAmount();
            txs.save(new WalletTransaction(payee, TxType.ESCROW_RELEASE, e.getAmount(), 0, balance, null, null,
                    e.getPayerId(), "교환 추가 금액 정산"));
            Map<String, Object> ref = Map.of("exchangeId", e.getExchangeId());
            wallet.event("ESCROW_RELEASED", payee, "추가 금액이 지갑에 들어왔어요",
                    WalletService.won(e.getAmount()) + "이 지갑에 들어왔어요. 지갑에서 출금할 수 있어요. (시연)", e.getId(), ref);
            wallet.event("ESCROW_RELEASED", e.getPayerId(), "정산되었어요",
                    "교환을 마쳐서 " + WalletService.won(e.getAmount()) + "이 상대에게 전달됐어요. (시연)", e.getId(), ref);
            return WalletDtos.EscrowView.from(e, null);
        });
    }

    /** 낸 사람에게 환불(금액 + 수수료 전부). 이미 환불됐으면 그대로. 이미 정산된 건은 409 */
    public WalletDtos.EscrowView refund(long exchangeId, String reason) {
        EscrowPayment found = latest(exchangeId);
        String why = reason == null || reason.isBlank() ? "교환이 취소되어" : reason.trim();
        return wallet.lockedBoth(found.getPayerId(), found.getPayeeId(), () -> {
            EscrowPayment e = escrows.findById(found.getId()).orElseThrow();
            if (EscrowPayment.REFUNDED.equals(e.getStatus())) {
                return WalletDtos.EscrowView.from(e, null);
            }
            if (!e.isHeld()) {
                throw ApiException.conflict("ALREADY_RELEASED", "이미 상대에게 전달된 결제라 환불할 수 없어요.");
            }
            e.refund();
            long payer = e.getPayerId();
            long before = wallet.balance(payer);
            // 낸 수단으로 돌려줌: 지갑 → 지갑, 토스 카드 → 토스 결제 취소(아래 이벤트), 가상계좌 → 지갑(계좌로 되돌리려면 환불 계좌가 필요해서)
            boolean toWallet = Catalog.WALLET.equals(e.getMethod()) || Catalog.TOSS_VA.equals(e.getMethod());
            long after = toWallet ? before + e.total() : before;
            txs.save(new WalletTransaction(payer, TxType.ESCROW_REFUND, e.total(), 0, after, e.getMethod(), null,
                    e.getPayeeId(), "교환 추가 금액 환불"));
            Map<String, Object> ref = Map.of("exchangeId", e.getExchangeId());
            wallet.event("ESCROW_REFUNDED", payer, "환불되었어요",
                    why + " " + WalletService.won(e.total()) + "을 "
                            + (toWallet ? "쓰바다 지갑" : Catalog.methodLabel(e.getMethod())) + "(으)로 돌려드렸어요."
                            + (Catalog.TOSS_CARD.equals(e.getMethod()) ? " (토스 테스트 결제 취소)" : " (시연)"), e.getId(), ref);
            if (Catalog.TOSS_CARD.equals(e.getMethod())) {
                events.publishEvent(new TossRefundNeeded(e.getExchangeId(), why));
            }
            wallet.event("ESCROW_REFUNDED", e.getPayeeId(), "추가 금액 결제가 취소되었어요",
                    why + " 상대가 낸 " + WalletService.won(e.getAmount()) + "은 환불되었어요. (시연)", e.getId(), ref);
            return WalletDtos.EscrowView.from(e, null);
        });
    }

    private EscrowPayment latest(long exchangeId) {
        return escrows.findFirstByExchangeIdOrderByIdDesc(exchangeId)
                .orElseThrow(() -> ApiException.notFound("ESCROW_NOT_FOUND", "이 교환의 결제 내역이 없어요."));
    }

    // ───────────── ④ 자동 정산 · 대사(reconcile) ─────────────

    @Scheduled(fixedDelayString = "${sbada.escrow.sweep-delay-ms:60000}",
            initialDelayString = "${sbada.escrow.sweep-delay-ms:60000}")
    public void scheduledSweep() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("안전결제 자동 처리 중 문제(다음 주기에 다시 시도): {}", e.getMessage());
        }
    }

    /** 처리한 건수를 돌려준다(테스트에서 직접 부른다) */
    public int sweep() {
        int done = 0;
        Instant now = Instant.now();
        for (EscrowPayment e : escrows.findTop50ByStatusOrderByIdAsc(EscrowPayment.HELD)) {
            if (!core.verifies()) { // 메인 서버 확인을 끈 경우: 시간만 보고 자동 정산
                if (now.isAfter(e.getReleaseAfter())) {
                    release(e.getExchangeId());
                    done++;
                }
                continue;
            }
            Optional<CoreClient.ExchangeInfo> x = core.findExchange(e.getExchangeId()); // 꺼져 있으면 503 → 이번 주기 끝
            String status = x.map(CoreClient.ExchangeInfo::status).orElse("MISSING");
            if ("REJECTED".equals(status) || "CANCELED".equals(status) || "MISSING".equals(status)) {
                refund(e.getExchangeId(), "교환이 취소되어");
                done++;
            } else if ("ACCEPTED".equals(status) && now.isAfter(e.getReleaseAfter())) {
                release(e.getExchangeId());
                done++;
            }
        }
        return done;
    }
}
