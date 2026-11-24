package com.sbada.events;

import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.Category;
import com.sbada.common.Json;
import com.sbada.exchange.ExchangeService;
import com.sbada.gateway.ProcessedEvent;
import com.sbada.gateway.ProcessedEventRepository;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.user.AppUserRepository;
import com.sbada.user.PreferenceService;

/**
 * 허브(Kafka)에서 듣는 이벤트 — 메인 서버(소비자 그룹 sbada-server) (2026-11-25 MSA 2단계)
 *  sbada.item.events     ITEM_STATUS_CHANGED(내려감 · 보류 · 거래 완료) → 걸린 교환 요청 취소 + 알림
 *                        ITEM_VIEWED → 선호도 학습(관심 +0.03)
 *  sbada.notifications   NOTIFY → 알림함 저장 + 실시간 · 푸시(물품 서버의 찜 · 보류 · 매칭 알림)
 *  sbada.payment.events  WITHDRAWN · ESCROW_HELD … → 지갑 알림 + 교환의 결제 상태
 * 결제 서비스의 HTTP 이벤트(/internal/events)도 같은 handleWallet 로 처리한다(Kafka 가 꺼져 있던 시절과 호환).
 * 같은 이벤트가 두 번 와도 processed_events 로 한 번만 반영한다.
 */
@Component
public class MainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(MainEventHandler.class);

    public static final Set<String> WALLET_EVENTS =
            Set.of("WITHDRAWN", "CHARGED", "PAYMENT_SENT", "PAYMENT_RECEIVED",
                    "ESCROW_HELD", "ESCROW_RELEASED", "ESCROW_REFUNDED", "ACCOUNT_VERIFY_SENT");
    private static final Set<String> GONE = Set.of("HIDDEN", "HELD", "TRADED");

    private final ProcessedEventRepository processed;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final PreferenceService preferences;
    private final ExchangeService exchanges;
    private final TransactionTemplate tx;

    public MainEventHandler(ProcessedEventRepository processed, AppUserRepository users,
                            NotificationService notifications, PreferenceService preferences,
                            ExchangeService exchanges, TransactionTemplate tx) {
        this.processed = processed;
        this.users = users;
        this.notifications = notifications;
        this.preferences = preferences;
        this.exchanges = exchanges;
        this.tx = tx;
    }

    @KafkaListener(topics = {Topics.ITEM, Topics.NOTIFICATIONS, Topics.PAYMENT}, groupId = "sbada-server")
    public void onMessage(String message) {
        handle(message);
    }

    /** 메시지 한 건(JSON). 처리했으면 true, 이미 처리한 것이면 false (테스트에서 직접 부른다) */
    public boolean handle(String message) {
        Map<String, Object> e = Json.readMap(message);
        Boolean done = tx.execute(s -> apply(e));
        return Boolean.TRUE.equals(done);
    }

    /** 같은 트랜잭션 안에서 처리(InternalController 의 HTTP 이벤트도 이것을 부른다) */
    public boolean apply(Map<String, Object> e) {
        String eventId = text(e.get("eventId"));
        String type = text(e.get("type"));
        if (eventId.isEmpty() || eventId.length() > 64 || processed.existsById(eventId)) {
            return false;
        }
        processed.save(new ProcessedEvent(eventId, type.length() > 40 ? type.substring(0, 40) : type));
        if (WALLET_EVENTS.contains(type)) {
            wallet(e, type);
        } else {
            switch (type) {
                case "ITEM_STATUS_CHANGED" -> {
                    if (GONE.contains(text(e.get("status")))) {
                        exchanges.cancelAllFor(num(e.get("itemId")), num(e.get("ownerId")), text(e.get("title")),
                                text(e.get("reason")));
                    }
                }
                case "ITEM_VIEWED" -> {
                    long viewer = num(e.get("userId"));
                    if (users.existsById(viewer)) {
                        preferences.learn(viewer, Category.parseLoose(e.get("category")), PreferenceService.Behavior.VIEW);
                    }
                }
                case "NOTIFY" -> notify(e);
                default -> log.debug("메인 서버와 상관없는 이벤트 {}", type);
            }
        }
        return true;
    }

    private void wallet(Map<String, Object> e, String type) {
        long userId = num(e.get("userId"));
        if (users.existsById(userId)) {
            Long refId = e.get("refId") instanceof Number n ? n.longValue() : null;
            notifications.notify(userId, NotificationType.WALLET, limit(text(e.get("title")), 60),
                    limit(text(e.get("body")), 200), "wallet", refId);
        }
        if (e.get("exchangeId") instanceof Number exchangeId) { // 안전결제 이벤트 → 교환 화면의 결제 상태
            exchanges.applyPaymentEvent(exchangeId.longValue(), type);
        }
    }

    private void notify(Map<String, Object> e) {
        long userId = num(e.get("userId"));
        NotificationType nt;
        try {
            nt = NotificationType.valueOf(text(e.get("notificationType")));
        } catch (IllegalArgumentException ex) {
            log.warn("모르는 알림 종류 {} — 건너뜀", e.get("notificationType"));
            return;
        }
        if (users.existsById(userId)) {
            Long refId = e.get("refId") instanceof Number n ? n.longValue() : null;
            String refType = e.get("refType") == null ? null : text(e.get("refType"));
            notifications.notify(userId, nt, limit(text(e.get("title")), 60), limit(text(e.get("body")), 200),
                    refType, refId);
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : o == null ? 0 : Long.parseLong(o.toString());
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
