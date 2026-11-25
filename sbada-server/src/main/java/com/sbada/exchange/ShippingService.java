package com.sbada.exchange;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.chat.ChatMessage;
import com.sbada.common.AfterCommit;
import com.sbada.common.ApiException;
import com.sbada.gateway.PaymentClient;
import com.sbada.items.ItemCatalog;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.shipping.Shipment;
import com.sbada.shipping.ShipmentRepository;
import com.sbada.shipping.TrackResult;
import com.sbada.shipping.TrackingService;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;

/**
 * 운송장 · 배송 추적 (2026-11-25 사용자 요청).
 *  ① 교환이 성사되면(ExchangeService.complete) 7일 안에 두 사람이 각자 보낸 물건의 운송장을 입력한다.
 *  ② 입력하면 택배 조회(TrackingService)로 '택배사에 접수된 진짜 번호'인지 확인 → 아니면 400 "운송장 번호를 다시 입력해 주세요"
 *  ③ 둘 다 입력하면 주기적으로 배송 현황을 묻고, 단계가 바뀔 때마다 채팅 · 알림으로 알린다.
 *  ④ 두 물건 모두 배송 완료 → 서버가 보관하던 추가요금을 받는 사람에게 정산(결제 서버) → 거래 완료 · 후기 쓰기
 *  ⑤ 7일 안에 둘 다 입력하지 않으면 "기간이 만료되었습니다" → 교환 취소 · 전액 환불 · 두 물건 다시 교환 가능
 */
@Service
public class ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingService.class);

    private final ExchangeRepository exchanges;
    private final ShipmentRepository shipments;
    private final AppUserRepository users;
    private final TrackingService tracking;
    private final ExchangeService exchangeService;
    private final ItemCatalog items;
    private final NotificationService notifications;
    private final PaymentClient payments;
    private final TransactionTemplate tx;
    private final boolean schedulerEnabled;
    /** 실제 택배사는 무료 한도(월 1,000건)를 아끼려고 드물게, 시연용 테스트 택배는 자주 묻는다 */
    private final Duration realRecheck;

    public ShippingService(ExchangeRepository exchanges, ShipmentRepository shipments, AppUserRepository users,
                           TrackingService tracking, ExchangeService exchangeService, ItemCatalog items,
                           NotificationService notifications, PaymentClient payments, TransactionTemplate tx,
                           @Value("${sbada.shipping.scheduler-enabled:true}") boolean schedulerEnabled,
                           @Value("${sbada.shipping.recheck-minutes:120}") long recheckMinutes) {
        this.exchanges = exchanges;
        this.shipments = shipments;
        this.users = users;
        this.tracking = tracking;
        this.exchangeService = exchangeService;
        this.items = items;
        this.notifications = notifications;
        this.payments = payments;
        this.tx = tx;
        this.schedulerEnabled = schedulerEnabled;
        this.realRecheck = Duration.ofMinutes(Math.max(10, recheckMinutes));
    }

    /** POST /api/exchanges/{id}/shipment {carrierCode, invoiceNo} — 내가 보낸 물건의 운송장 입력(다시 입력하면 고쳐 씀) */
    @Transactional
    public ExchangeDtos.ExchangeView submit(long exchangeId, long userId, String carrierCode, String invoiceNo) {
        ExchangeRequest request = exchangeService.require(exchangeId);
        if (!request.isParticipant(userId)) {
            throw ApiException.forbidden("NOT_PARTICIPANT", "교환 당사자만 운송장을 입력할 수 있어요.");
        }
        switch (request.getDealStage()) {
            case NONE -> throw ApiException.conflict("NOT_SHIPPING", "교환이 성사된 뒤에 운송장을 입력할 수 있어요.");
            case COMPLETED -> throw ApiException.conflict("ALREADY_COMPLETED", "이미 두 물건 모두 도착해 거래가 끝났어요.");
            case EXPIRED -> throw ApiException.conflict("SHIP_EXPIRED", "기간이 만료되었습니다.");
            default -> { }
        }
        if (request.getShipDeadline() != null && Instant.now().isAfter(request.getShipDeadline())) {
            throw ApiException.conflict("SHIP_EXPIRED", "기간이 만료되었습니다. (운송장 입력은 성사 후 7일까지예요)");
        }
        String code = carrierCode == null ? "" : carrierCode.trim();
        String invoice = invoiceNo == null ? "" : invoiceNo.replaceAll("[^0-9A-Za-z]", "");
        if (invoice.length() < 8 || invoice.length() > 20) {
            throw new ApiException(400, "INVALID_INVOICE", "운송장 번호를 다시 입력해 주세요. (숫자 8~20자리)",
                    Map.of("invoiceNo", "운송장 번호를 다시 입력해 주세요."));
        }
        String carrierName = tracking.carrierName(code);
        Shipment s = shipments.findByExchangeIdAndSenderId(exchangeId, userId).orElse(null);
        if (s != null && s.isDelivered()) {
            throw ApiException.conflict("ALREADY_DELIVERED", "이미 배송이 완료된 운송장은 바꿀 수 없어요.");
        }
        boolean same = s != null && invoice.equals(s.getInvoiceNo()) && code.equals(s.getCarrierCode());
        TrackResult first = tracking.track(code, invoice, same ? s.getVerifiedAt() : Instant.now());
        if (!first.found()) {
            throw new ApiException(400, "INVALID_INVOICE", first.error(), Map.of("invoiceNo", first.error()));
        }
        AppUser me = users.findById(userId).orElseThrow();
        boolean fresh = s == null;
        if (fresh) {
            s = new Shipment(request, me);
        }
        s.register(code, carrierName, invoice, first);
        shipments.save(s);
        if (fresh) {
            request.getShipments().add(s);
        }
        exchangeService.postCard(request, ChatMessage.Type.SHIPPING, String.format("🚚 %s님이 운송장을 등록했어요 · %s %s — %s",
                me.getNickname(), carrierName, s.maskedInvoice(), TrackResult.levelLabel(s.getLevel())));
        notifications.notify(request.otherOf(userId), NotificationType.DELIVERY, "상대가 운송장을 입력했어요",
                String.format("%s님이 보낸 물건: %s %s", me.getNickname(), carrierName, s.maskedInvoice()),
                "EXCHANGE", request.getId());
        if (request.getShipments().size() == 2) {
            exchangeService.postSystem(request, "📮 두 분 모두 운송장을 입력했어요. 배송 현황이 바뀌면 채팅과 알림으로 알려 드릴게요.");
        }
        checkCompleted(request);
        exchangeService.publish(request);
        return exchangeService.view(request, userId);
    }

    /** POST /api/exchanges/{id}/shipment/refresh — 배송 현황 지금 다시 묻기(같은 운송장은 1분에 한 번만) */
    @Transactional
    public ExchangeDtos.ExchangeView refresh(long exchangeId, long userId) {
        ExchangeRequest request = exchangeService.require(exchangeId);
        if (!request.isParticipant(userId)) {
            throw ApiException.forbidden("NOT_PARTICIPANT", "교환 당사자만 배송 현황을 볼 수 있어요.");
        }
        boolean changed = false;
        for (Shipment s : request.getShipments()) {
            if (!s.isDelivered() && s.getCheckedAt().isBefore(Instant.now().minusSeconds(60))) {
                changed |= poll(request, s);
            }
        }
        if (changed) {
            checkCompleted(request);
            exchangeService.publish(request);
        }
        return exchangeService.view(request, userId);
    }

    /** 1분마다: 7일 만료 검사 + 묻을 때가 된 운송장 조회. 교환 하나씩 따로 트랜잭션(하나가 실패해도 나머지는 계속) */
    @Scheduled(initialDelayString = "${sbada.shipping.initial-delay-ms:30000}", fixedDelayString = "${sbada.shipping.poll-ms:60000}")
    public void scheduled() {
        if (schedulerEnabled) {
            runOnce();
        }
    }

    /** 예약 작업 한 번(테스트에서 직접 부른다). 처리한 교환 수 */
    public int runOnce() {
        int n = 0;
        List<Long> expired = tx.execute(s -> exchanges.findByDealStageAndShipDeadlineBefore(DealStage.SHIPPING, Instant.now())
                .stream().filter(e -> e.getShipments().size() < 2).map(ExchangeRequest::getId).toList());
        for (Long id : expired == null ? List.<Long>of() : expired) {
            try {
                tx.executeWithoutResult(s -> expire(exchangeService.require(id)));
                n++;
            } catch (RuntimeException e) {
                log.warn("운송장 만료 처리 실패(교환 #{}): {}", id, e.getMessage());
            }
        }
        List<Long> due = tx.execute(s -> shipments.findOpen(DealStage.SHIPPING).stream()
                .filter(this::isDue).map(sh -> sh.getExchange().getId()).distinct().toList());
        for (Long id : due == null ? List.<Long>of() : due) {
            try {
                tx.executeWithoutResult(s -> {
                    ExchangeRequest request = exchangeService.require(id);
                    boolean changed = false;
                    for (Shipment sh : request.getShipments()) {
                        if (!sh.isDelivered() && isDue(sh)) {
                            changed |= poll(request, sh);
                        }
                    }
                    if (changed) {
                        checkCompleted(request);
                        exchangeService.publish(request);
                    }
                });
                n++;
            } catch (RuntimeException e) {
                log.warn("배송 현황 조회 실패(교환 #{}): {}", id, e.getMessage());
            }
        }
        return n;
    }

    private boolean isDue(Shipment s) {
        Duration every = TrackingService.TEST_CARRIER.equals(s.getCarrierCode()) ? Duration.ofSeconds(50) : realRecheck;
        return s.getCheckedAt().isBefore(Instant.now().minus(every));
    }

    /** 택배사에 한 번 묻고, 단계가 바뀌었으면 채팅 · 알림. 바뀌었으면 true */
    private boolean poll(ExchangeRequest request, Shipment s) {
        TrackResult r;
        try {
            r = tracking.track(s.getCarrierCode(), s.getInvoiceNo(), s.getVerifiedAt());
        } catch (ApiException e) {
            log.warn("택배 조회 실패(교환 #{}): {}", request.getId(), e.getMessage());
            return false;
        }
        if (!s.apply(r)) {
            return false;
        }
        String who = s.getSender().getNickname();
        String where = s.getLastWhere() == null ? "" : " · " + s.getLastWhere();
        exchangeService.postCard(request, ChatMessage.Type.SHIPPING, String.format("🚚 배송 현황 · %s님이 보낸 물건: %s%s",
                who, s.getLastDetail() == null ? TrackResult.levelLabel(s.getLevel()) : s.getLastDetail(), where));
        String title = s.isDelivered() ? "물건이 도착했어요" : "배송 현황이 바뀌었어요";
        String body = String.format("%s님이 보낸 물건 — %s", who, TrackResult.levelLabel(s.getLevel()));
        notifications.notify(request.getRequester().getId(), NotificationType.DELIVERY, title, body, "EXCHANGE", request.getId());
        notifications.notify(request.getTargetOwner().getId(), NotificationType.DELIVERY, title, body, "EXCHANGE", request.getId());
        return true;
    }

    /** 두 물건 모두 배송 완료면: 보관 중인 추가요금 정산 → 거래 완료 · 후기 안내 */
    private void checkCompleted(ExchangeRequest request) {
        if (request.getDealStage() != DealStage.SHIPPING || request.getShipments().size() < 2
                || !request.getShipments().stream().allMatch(Shipment::isDelivered)) {
            return;
        }
        if (request.payment() == PaymentStatus.PAID_HELD) {
            payments.release(request.getId()); // 결제 서버가 꺼져 있으면 예외 → 다음 조회 때 다시
            exchangeService.markPayment(request, PaymentStatus.RELEASED);
        }
        request.markCompleted();
        exchangeService.postCard(request, ChatMessage.Type.SHIPPING,
                "✅ 두 물건 모두 도착했어요! 거래가 완료되었어요. 서로에게 거래 후기를 남겨 주세요.");
        for (long uid : List.of(request.getRequester().getId(), request.getTargetOwner().getId())) {
            notifications.notify(uid, NotificationType.DELIVERY, "거래가 완료되었어요 🎉",
                    "두 물건 모두 도착했어요. 거래 후기를 남겨 주세요.", "EXCHANGE", request.getId());
        }
    }

    /** 7일 만료: 교환 취소 · 전액 환불 · 두 물건 다시 교환 가능 */
    void expire(ExchangeRequest request) {
        if (request.getDealStage() != DealStage.SHIPPING) {
            return;
        }
        request.expireShipping("운송장 입력 기간(7일)이 지나 거래가 취소되었어요.");
        exchangeService.postCard(request, ChatMessage.Type.SHIPPING,
                "⏰ 기간이 만료되었습니다. 7일 안에 두 분 모두 운송장을 입력하지 않아 거래가 취소되었어요. 낸 금액은 전액 환불돼요.");
        exchangeService.refundIfPaid(request, "운송장 입력 기간이 지나");
        long id = request.getId();
        long offered = request.getOfferedItemId();
        long target = request.getTargetItemId();
        AfterCommit.run(() -> {
            try {
                items.reopenTrade(id, offered, target);
            } catch (RuntimeException e) {
                log.warn("만료된 교환 #{} 의 물건을 다시 열지 못했어요(물품 서버): {}", id, e.getMessage());
            }
        });
        for (long uid : List.of(request.getRequester().getId(), request.getTargetOwner().getId())) {
            notifications.notify(uid, NotificationType.DELIVERY, "기간이 만료되었습니다",
                    "운송장 입력 기간(7일)이 지나 거래가 취소되었어요. 낸 금액은 전액 환불돼요.", "EXCHANGE", request.getId());
        }
        exchangeService.publish(request);
    }
}
