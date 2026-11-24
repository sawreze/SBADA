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
import com.sbada.events.MainEventHandler;
import com.sbada.exchange.ExchangeService;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.user.AppUserRepository;

/**
 * 서비스 간 내부 API — 결제 서비스(sbada-payment)만 부른다. 앱에서는 쓰지 않는다.
 *  GET  /internal/users/{id}   회원이 있는지(추가 요금을 받을 사람 확인)
 *  POST /internal/events       결제 서비스 이벤트 수신 → 알림(알림함 저장 + 실시간 · 푸시)
 *     {"eventId":"uuid","type":"WITHDRAWN","userId":7,"title":"출금되었습니다","body":"…","refId":12}
 *  GET  /internal/exchanges/{id}  교환의 추가 금액 조건(누가 · 누구에게 · 얼마) — 결제 서비스가 안전결제 전에 확인
 *  2026-11-25: ESCROW_HELD · ESCROW_RELEASED · ESCROW_REFUNDED(exchangeId 포함) → 교환의 결제 상태도 바꾼다,
 *              ACCOUNT_VERIFY_SENT(1원 인증 입금자명 알림)
 *  인증: 헤더 X-Internal-Token (공유 비밀 파일)
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private static final Set<String> WALLET_EVENTS = MainEventHandler.WALLET_EVENTS;

    private final InternalToken token;
    private final AppUserRepository users;
    private final ExchangeService exchanges;
    private final MainEventHandler events;
    private final LegacyExport legacy;

    public InternalController(InternalToken token, AppUserRepository users, ExchangeService exchanges,
                              MainEventHandler events, LegacyExport legacy) {
        this.token = token;
        this.users = users;
        this.exchanges = exchanges;
        this.events = events;
        this.legacy = legacy;
    }

    /** 물품 서버가 처음 켜질 때 한 번: 옛 물건 · 사진 · 찜 · AI 분석 · 판매자를 가져간다(MSA 2단계 데이터 이전) */
    @GetMapping("/legacy/items-export")
    public Map<String, Object> legacyItems(@RequestHeader(value = InternalToken.HEADER, required = false) String given) {
        token.check(given);
        return legacy.export();
    }

    @GetMapping("/exchanges/{id}")
    public ResponseEntity<?> exchange(@RequestHeader(value = InternalToken.HEADER, required = false) String given,
                                      @PathVariable long id) {
        token.check(given);
        return exchanges.internalInfo(id)
                .<ResponseEntity<?>>map(m -> ResponseEntity.ok(m))
                .orElseGet(() -> ResponseEntity.status(404).body(ErrorResponse.of("EXCHANGE_NOT_FOUND", "교환이 없습니다.")));
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
        // 2026-11-25: Kafka 로 오는 결제 이벤트와 같은 처리(MainEventHandler). 이미 처리한 이벤트면 duplicate
        return Map.of("status", events.apply(e) ? "ok" : "duplicate");
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
