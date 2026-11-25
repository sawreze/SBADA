package com.sbada.payment.toss;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.payment.common.ApiException;
import com.sbada.payment.wallet.Catalog;
import com.sbada.payment.wallet.EscrowService;

/**
 * 토스페이먼츠로 교환 추가요금 결제 (2026-11-25 사용자 요청, 테스트 키).
 *
 *  ① prepare  : 낼 사람 · 금액을 메인 서버 교환 조건으로 확인 → 주문(READY) → 앱이 토스 결제창을 연다(클라이언트 키)
 *  ② confirm  : 결제창이 돌려준 paymentKey 로 토스 승인 API(시크릿 키)
 *               · 카드 · 간편결제 → DONE → 안전결제 보관(TOSS_CARD) → 허브로 ESCROW_HELD → 메인 서버가 교환을 진행
 *               · 가상계좌 → WAITING_FOR_DEPOSIT(은행 · 계좌번호 · 기한을 앱에 보여 줌)
 *  ③ 입금 확인 : 토스 웹훅(POST /api/payments/toss/webhook) 또는 1분마다 토스에 다시 물어서(맥 로컬은 웹훅을 못 받으므로)
 *               DONE 이면 보관(TOSS_VA) → 교환 진행. 웹훅 본문은 믿지 않고 항상 토스에 다시 조회한다.
 *  ④ 환불     : 교환이 취소 · 만료되어 보관금을 환불하면 토스 결제를 취소(카드), 가상계좌는 지갑으로
 *
 * 돈의 흐름(테스트): 테스트 키라 실제 돈은 움직이지 않는다. 라이브로 바꾸면 결제 금액은 토스 가맹점 정산 계좌(사용자 계좌)로
 * 들어오고, 상대에게 주는 정산은 지금처럼 쓰바다 지갑(출금)으로 처리된다 — 실제 송금은 토스 '지급대행' 계약이 필요하다.
 */
@Service
public class TossService {

    private static final Logger log = LoggerFactory.getLogger(TossService.class);

    private final TossClient toss;
    private final TossOrderRepository orders;
    private final EscrowService escrow;
    private final TransactionTemplate tx;
    private final com.sbada.payment.wallet.WalletService wallet;
    private final int vaValidHours;

    public TossService(TossClient toss, TossOrderRepository orders, EscrowService escrow, TransactionTemplate tx,
                       com.sbada.payment.wallet.WalletService wallet,
                       @Value("${sbada.toss.va-valid-hours:24}") int vaValidHours) {
        this.toss = toss;
        this.orders = orders;
        this.escrow = escrow;
        this.tx = tx;
        this.wallet = wallet;
        this.vaValidHours = vaValidHours;
    }

    /** widget = 결제위젯 연동 키(test_gck_)라 앱이 결제위젯 SDK 를 써야 하는지(아니면 API 개별 연동 키 → 결제창 SDK) */
    public record ConfigView(boolean enabled, String clientKey, boolean testMode, String label, boolean widget) {
    }

    public record PrepareRequest(Long exchangeId, String method, Long amount, Long toUserId) {
    }

    public record OrderView(String orderId, long exchangeId, String orderName, long amount, long fee, long total,
                            String method, String status, String clientKey, boolean widget, String customerKey, int vaValidHours,
                            VirtualAccount virtualAccount, String message) {
    }

    public record VirtualAccount(String bank, String accountNumber, String holder, String dueDate) {
    }

    public ConfigView config() {
        return new ConfigView(toss.configured(), toss.configured() ? toss.clientKey() : null, true,
                "토스페이먼츠 테스트 결제 · 실제 돈은 나가지 않아요", toss.clientKey().contains("_gck_"));
    }

    /** ① 주문 만들기 */
    public OrderView prepare(long userId, PrepareRequest in) {
        if (!toss.configured()) {
            throw new ApiException(503, "TOSS_NOT_CONFIGURED",
                    "토스 테스트 키가 아직 없어요. 맥에서 결제_택배_키_설정.command 로 키를 넣고 서버를 다시 켜 주세요.");
        }
        if (in.exchangeId() == null) {
            throw ApiException.badRequest("EXCHANGE_REQUIRED", "교환 번호가 필요해요.");
        }
        String method = in.method() == null ? "CARD" : in.method().trim().toUpperCase();
        if (!method.equals("CARD") && !method.equals("VIRTUAL_ACCOUNT")) {
            throw ApiException.badRequest("INVALID_METHOD", "결제 방법은 CARD(토스 결제창) 또는 VIRTUAL_ACCOUNT(가상계좌)예요.");
        }
        long exchangeId = in.exchangeId();
        if (escrow.alreadyPaid(exchangeId)) {
            throw ApiException.conflict("ALREADY_PAID", "이미 결제된 교환이에요.");
        }
        // 이미 발급된 가상계좌가 입금을 기다리는 중이면 그 계좌를 다시 보여 준다(새로 만들지 않음)
        TossOrder waiting = orders.findFirstByExchangeIdAndStatusOrderByIdDesc(exchangeId, TossOrder.WAITING).orElse(null);
        if (waiting != null && waiting.getPayerId() == userId) {
            return view(waiting, "이미 발급된 가상계좌로 입금해 주세요.");
        }
        EscrowService.Terms terms = escrow.termsFor(userId, exchangeId, in.amount(), in.toUserId());
        long fee = wallet.feeOf(terms.amount());
        String orderId = "sbada-" + exchangeId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TossOrder o = tx.execute(s -> orders.save(new TossOrder(orderId, exchangeId, userId, terms.payeeId(), terms.amount(), fee, method)));
        return view(o, null);
    }

    /** ② 결제창에서 돌아온 뒤 승인 */
    public OrderView confirm(long userId, String paymentKey, String orderId, long amount) {
        TossOrder o = orders.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "주문을 찾을 수 없어요."));
        if (o.getPayerId() != userId) {
            throw new ApiException(403, "NOT_PAYER", "이 주문을 결제한 사람이 아니에요.");
        }
        if (o.getTotal() != amount) { // 결제창에서 금액을 바꿔 치는 것을 막는다
            throw ApiException.badRequest("AMOUNT_MISMATCH", "결제 금액이 주문과 달라요.");
        }
        if (TossOrder.DONE.equals(o.getStatus()) || TossOrder.WAITING.equals(o.getStatus())) {
            return view(o, null); // 같은 승인이 두 번 와도 한 번만(멱등)
        }
        if (!TossOrder.READY.equals(o.getStatus())) {
            throw ApiException.conflict("ORDER_CLOSED", "이미 끝난 주문이에요(" + o.getStatus() + ").");
        }
        Map<String, Object> payment;
        try {
            payment = toss.confirm(paymentKey, orderId, amount);
        } catch (ApiException e) {
            tx.executeWithoutResult(s -> orders.findById(o.getId()).ifPresent(x -> x.close(TossOrder.FAILED, e.getMessage())));
            throw e;
        }
        return apply(o.getId(), payment);
    }

    /** 토스가 알려 준 결제 상태를 주문 · 보관에 반영(승인 · 웹훅 · 다시 묻기 공통) */
    OrderView apply(long orderPk, Map<String, Object> payment) {
        String status = String.valueOf(payment.getOrDefault("status", ""));
        TossOrder o = tx.execute(s -> {
            TossOrder x = orders.findById(orderPk).orElseThrow();
            x.paymentKey(str(payment.get("paymentKey")));
            x.actualMethod(str(payment.get("method"))); // 결제위젯: 사용자가 고른 실제 수단(카드 · 가상계좌 · 간편결제)
            if ("WAITING_FOR_DEPOSIT".equals(status) && !TossOrder.DONE.equals(x.getStatus())) {
                Map<?, ?> va = payment.get("virtualAccount") instanceof Map<?, ?> m ? m : Map.of();
                x.waiting(bankName(str(va.get("bankCode"))), str(va.get("accountNumber")), str(va.get("customerName")),
                        str(va.get("dueDate")));
            } else if ("CANCELED".equals(status) || "ABORTED".equals(status) || "EXPIRED".equals(status)
                    || "PARTIAL_CANCELED".equals(status)) {
                if (!TossOrder.DONE.equals(x.getStatus())) {
                    x.close(TossOrder.CANCELED, "토스 결제 상태: " + status);
                }
            } else {
                x.checked();
            }
            return x;
        });
        if ("DONE".equals(status) && !TossOrder.DONE.equals(o.getStatus())) {
            return finish(o);
        }
        return view(o, null);
    }

    /** 결제 완료 → 안전결제 보관(→ 허브 ESCROW_HELD → 메인 서버가 교환 진행). 교환이 이미 끝났으면 토스 결제 취소 */
    private OrderView finish(TossOrder o) {
        String method = o.isVirtualAccount() ? Catalog.TOSS_VA : Catalog.TOSS_CARD;
        try {
            escrow.holdVerifiedExternal(o.getPayerId(), o.getExchangeId(), o.getAmount(), o.getPayeeId(), method,
                    "토스페이먼츠 " + (o.isVirtualAccount() ? "가상계좌" : "결제") + " " + o.getOrderId());
        } catch (ApiException e) {
            if (!"ALREADY_PAID".equals(e.getCode())) {
                log.warn("토스 결제 #{} 를 보관하지 못해 취소해요: {}", o.getOrderId(), e.getMessage());
                cancelQuietly(o, "교환을 진행할 수 없어 자동 취소: " + e.getMessage());
                tx.executeWithoutResult(s -> orders.findById(o.getId()).ifPresent(x -> x.close(TossOrder.CANCELED, e.getMessage())));
                return view(orders.findById(o.getId()).orElseThrow(), "교환을 진행할 수 없어 결제를 취소했어요: " + e.getMessage());
            }
        }
        TossOrder done = tx.execute(s -> {
            TossOrder x = orders.findById(o.getId()).orElseThrow();
            x.done();
            return x;
        });
        return view(done, "결제가 확인되었어요. 교환이 진행돼요.");
    }

    /** ③ 웹훅: 본문은 주문번호만 믿고, 상태는 토스에 다시 물어서 반영 */
    public void webhook(Map<String, Object> body) {
        String orderId = str(body.get("orderId"));
        if (orderId == null && body.get("data") instanceof Map<?, ?> data) {
            orderId = str(data.get("orderId"));
        }
        if (orderId == null) {
            return;
        }
        TossOrder o = orders.findByOrderId(orderId).orElse(null);
        if (o == null || TossOrder.DONE.equals(o.getStatus())) {
            return;
        }
        apply(o.getId(), toss.getByOrderId(orderId));
    }

    /** ③ 가상계좌 입금을 1분마다 다시 확인(웹훅을 못 받는 맥 로컬용) */
    @Scheduled(initialDelayString = "${sbada.toss.poll-ms:60000}", fixedDelayString = "${sbada.toss.poll-ms:60000}")
    public void pollWaiting() {
        if (!toss.configured()) {
            return;
        }
        for (TossOrder o : orders.findTop50ByStatusOrderByIdAsc(TossOrder.WAITING)) {
            if (o.getCheckedAt().isAfter(Instant.now().minusSeconds(50))) {
                continue;
            }
            try {
                apply(o.getId(), toss.getByOrderId(o.getOrderId()));
            } catch (RuntimeException e) {
                log.warn("가상계좌 입금 확인 실패({}): {}", o.getOrderId(), e.getMessage());
                tx.executeWithoutResult(s -> orders.findById(o.getId()).ifPresent(TossOrder::checked));
            }
        }
    }

    /** 앱이 교환 카드에서 '가상계좌 입금 대기' 등을 다시 보여 줄 때 */
    public OrderView latestFor(long userId, long exchangeId) {
        TossOrder o = orders.findFirstByExchangeIdOrderByIdDesc(exchangeId)
                .filter(x -> x.getPayerId() == userId || x.getPayeeId() == userId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "이 교환의 토스 결제가 없어요."));
        return view(o, null);
    }

    /** ④ 보관금 환불 → 토스 결제 취소(카드 · 간편결제) */
    @EventListener
    public void onRefund(EscrowService.TossRefundNeeded e) {
        orders.findFirstByExchangeIdAndStatusOrderByIdDesc(e.exchangeId(), TossOrder.DONE)
                .filter(o -> !o.isVirtualAccount())
                .ifPresent(o -> {
                    if (cancelQuietly(o, e.reason())) {
                        tx.executeWithoutResult(s -> orders.findById(o.getId()).ifPresent(x -> x.close(TossOrder.CANCELED, "환불: " + e.reason())));
                    }
                });
    }

    private boolean cancelQuietly(TossOrder o, String reason) {
        if (o.getPaymentKey() == null) {
            return false;
        }
        try {
            toss.cancel(o.getPaymentKey(), reason);
            return true;
        } catch (RuntimeException ex) {
            log.warn("토스 결제 취소 실패({}): {}", o.getOrderId(), ex.getMessage());
            return false;
        }
    }

    private OrderView view(TossOrder o, String message) {
        VirtualAccount va = o.getVaAccountNumber() == null ? null
                : new VirtualAccount(o.getVaBank(), o.getVaAccountNumber(), o.getVaHolder(), o.getVaDueDate());
        return new OrderView(o.getOrderId(), o.getExchangeId(), "쓰바다 교환 #" + o.getExchangeId() + " 추가요금",
                o.getAmount(), o.getFee(), o.getTotal(), o.getMethod(), o.getStatus(), toss.clientKey(),
                toss.clientKey().contains("_gck_"),
                "sbada-user-" + o.getPayerId(), vaValidHours, va, message != null ? message : o.getMessage());
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() || "null".equals(String.valueOf(o)) ? null : String.valueOf(o);
    }

    /** 토스 은행 코드 → 이름(가상계좌 안내용). 모르는 코드는 '은행 코드 NN' */
    static String bankName(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "39" -> "경남은행";
            case "34" -> "광주은행";
            case "12" -> "단위농협";
            case "32" -> "부산은행";
            case "45" -> "새마을금고";
            case "64" -> "산림조합";
            case "88" -> "신한은행";
            case "48" -> "신협";
            case "27" -> "씨티은행";
            case "20" -> "우리은행";
            case "71" -> "우체국";
            case "50" -> "저축은행";
            case "37" -> "전북은행";
            case "35" -> "제주은행";
            case "90" -> "카카오뱅크";
            case "89" -> "케이뱅크";
            case "92" -> "토스뱅크";
            case "81" -> "하나은행";
            case "54" -> "HSBC";
            case "03" -> "IBK기업은행";
            case "06", "04" -> "KB국민은행";
            case "31" -> "대구은행";
            case "02" -> "산업은행";
            case "11" -> "NH농협은행";
            case "23" -> "SC제일은행";
            case "07" -> "수협은행";
            default -> "은행 코드 " + code;
        };
    }
}
