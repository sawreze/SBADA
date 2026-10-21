package com.sbada.notification;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * ┌───────────────────────────────────────────────────────────┐
 * │ 알림 API   (🔒 모두 로그인 필요)                              │
 * └───────────────────────────────────────────────────────────┘
 *  GET  /api/notifications                알림함(최근 100개 + 안 읽은 수)
 *  GET  /api/notifications/unread-count   안 읽은 수만(홈의 종 아이콘 점)
 *  POST /api/notifications/{id}/read      알림 하나 읽음
 *  POST /api/notifications/read-all       모두 읽음
 *  POST /api/devices                      FCM 기기 토큰 등록(앱이 꺼져 있을 때 푸시용)
 *
 * [프로그래밍 설명 — 알림 한 개가 만들어지는 곳]
 *  - 이 Controller 는 '조회·읽음'만 한다. 알림을 '만드는' 것은 다른 서비스가 NotificationService.notify(…) 를
 *    부를 때다(교환 요청·수락·거절·취소, 새 물건 매칭).
 *  - notify 는 ① DB 저장(알림함) → ② 커밋 후 접속 중이면 WebSocket {"type":"notification", …} 전송
 *    → ③ 접속 중이 아니면 FCM 푸시. 앱은 ②를 받으면 Android 알림 표시줄에 띄운다.
 *  - 남의 알림을 읽음 처리하지 못하게 주인 검사(403) 를 한다.
 */
@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /**
     * ■ 알림함   GET /api/notifications
     * 응답 200 — NotificationDtos.NotificationList
     *   {
     *     "notifications": [
     *       {"id": 21, "type": "EXCHANGE_REQUESTED", "title": "교환 요청이 왔어요",
     *        "body": "준호님이 '헬멧'(으)로 '에어팟'을(를) 바꾸고 싶어해요. …",
     *        "refType": "EXCHANGE", "refId": 7,          // 눌렀을 때 열 화면: EXCHANGE(제안 카드) / ITEM(물건 상세)
     *        "read": false, "createdAt": "2026-10-21T04:00:00Z"}
     *     ],
     *     "unread": 3
     *   }
     *   type 종류: EXCHANGE_REQUESTED · EXCHANGE_ACCEPTED · EXCHANGE_REJECTED · EXCHANGE_CANCELED · MATCH_SUGGESTED
     */
    @GetMapping("/api/notifications")
    public NotificationDtos.NotificationList list(Authentication auth) {
        return service.list((Long) auth.getPrincipal());
    }

    /**
     * ■ 안 읽은 알림 수   GET /api/notifications/unread-count
     * 응답 200 — {"unread": 3}
     * (목록 전체를 받지 않고 숫자만 받아 배지를 그릴 때 쓴다 → 응답이 가볍다)
     */
    @GetMapping("/api/notifications/unread-count")
    public NotificationDtos.UnreadCount unread(Authentication auth) {
        return new NotificationDtos.UnreadCount(service.unread((Long) auth.getPrincipal()));
    }

    /**
     * ■ 알림 하나 읽음   POST /api/notifications/{id}/read   (본문 없음)
     * 응답 200 — 바뀐 NotificationView("read": true)
     * 오류: 403 NOT_MY_NOTIFICATION(남의 알림), 404 NOTIFICATION_NOT_FOUND
     */
    @PostMapping("/api/notifications/{id}/read")
    public NotificationDtos.NotificationView read(@PathVariable long id, Authentication auth) {
        return service.markRead(id, (Long) auth.getPrincipal());
    }

    /**
     * ■ 모두 읽음   POST /api/notifications/read-all   (본문 없음)
     * 응답 200 — {"updated": 3}   (실제로 읽음으로 바뀐 개수 = UPDATE 된 행 수)
     */
    @PostMapping("/api/notifications/read-all")
    public Map<String, Integer> readAll(Authentication auth) {
        return Map.of("updated", service.markAllRead((Long) auth.getPrincipal()));
    }

    /**
     * ■ 기기 토큰 등록   POST /api/devices
     *   앱이 FCM 토큰을 받으면 여기에 등록한다(FCM 을 켠 경우).
     *
     * 요청 본문 — NotificationDtos.DeviceRequest
     *   {"token": "fcm-기기-토큰-문자열", "platform": "android"}   (platform 생략 시 "android")
     *   같은 토큰이 이미 있으면 새 행을 만들지 않고 주인만 바꾼다(한 폰에서 계정을 바꿔 로그인한 경우).
     *
     * 응답 200 — {"registered": true, "fcmEnabled": false}
     *   fcmEnabled: 서버에 Firebase 서비스 계정이 설정돼 실제로 푸시를 보낼 수 있는지
     * 오류: 400 VALIDATION_FAILED(token 비어 있음·300자 초과)
     */
    @PostMapping("/api/devices")
    public ResponseEntity<Map<String, Object>> device(@Valid @RequestBody NotificationDtos.DeviceRequest input,
                                                      Authentication auth) {
        service.registerDevice((Long) auth.getPrincipal(), input.token().trim(),
                input.platform() == null ? "android" : input.platform());
        return ResponseEntity.ok(Map.of("registered", true, "fcmEnabled", service.fcmEnabled()));
    }
}
