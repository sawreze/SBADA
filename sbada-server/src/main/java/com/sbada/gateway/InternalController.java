package com.sbada.gateway;

import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.ApiException;
import com.sbada.common.ErrorResponse;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.user.AppUserRepository;

/**
 * 서비스 간 내부 API — 결제 서비스(sbada-payment)만 부른다. 앱에서는 쓰지 않는다.
 *  GET  /internal/users/{id}   회원이 있는지(추가 요금을 받을 사람 확인)
 *  POST /internal/events       결제 서비스 이벤트 수신 → 알림(알림함 저장 + 실시간 · 푸시)
 *     {"eventId":"uuid","type":"WITHDRAWN","userId":7,"title":"출금되었습니다","body":"…","refId":12}
 *  인증: 헤더 X-Internal-Token (공유 비밀 파일)
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final Set<String> WALLET_EVENTS =
            Set.of("WITHDRAWN", "CHARGED", "PAYMENT_SENT", "PAYMENT_RECEIVED");

    private final InternalToken token;
    private final AppUserRepository users;
    private final NotificationService notifications;
    private final ProcessedEventRepository processed;

    public InternalController(InternalToken token, AppUserRepository users, NotificationService notifications,
                              ProcessedEventRepository processed) {
        this.token = token;
        this.users = users;
        this.notifications = notifications;
        this.processed = processed;
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<?> user(@RequestHeader(value = InternalToken.HEADER, required = false) String given,
                                  @PathVariable long id) {
        token.check(given);
        return users.findById(id)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of("id", u.getId(), "nickname", u.getNickname())))
                .orElseGet(() -> ResponseEntity.status(404).body(ErrorResponse.of("USER_NOT_FOUND", "회원이 없습니다.")));
    }

    @PostMapping("/events")
    @Transactional
    public Map<String, Object> event(@RequestHeader(value = InternalToken.HEADER, required = false) String given,
                                     @RequestBody Map<String, Object> e) {
        token.check(given);
        String eventId = text(e.get("eventId"));
        String type = text(e.get("type"));
        if (eventId.isEmpty() || eventId.length() > 64 || !WALLET_EVENTS.contains(type)
                || !(e.get("userId") instanceof Number)) {
            throw ApiException.badRequest("INVALID_EVENT", "이벤트 형식이 올바르지 않습니다.");
        }
        if (processed.existsById(eventId)) {
            return Map.of("status", "duplicate"); // 이미 처리함 → 결제 서비스는 '보냄'으로 표시하고 끝
        }
        processed.save(new ProcessedEvent(eventId, type));
        long userId = ((Number) e.get("userId")).longValue();
        if (users.existsById(userId)) {
            Long refId = e.get("refId") instanceof Number n ? n.longValue() : null;
            notifications.notify(userId, NotificationType.WALLET, limit(text(e.get("title")), 60),
                    limit(text(e.get("body")), 200), "wallet", refId);
        }
        return Map.of("status", "ok");
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
