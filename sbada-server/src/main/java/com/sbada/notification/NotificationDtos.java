package com.sbada.notification;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 알림 API 의 요청/응답 JSON 모양. 예시는 NotificationController 주석 참고.
 * NotificationView 는 REST 응답과 WebSocket {"type":"notification","notification":{…}} 이벤트에 똑같이 쓰인다.
 */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationView(long id, String type, String title, String body, String refType, Long refId,
                                   boolean read, String createdAt) {
        public static NotificationView from(Notification n) {
            return new NotificationView(n.getId(), n.getType().name(), n.getTitle(), n.getBody(),
                    n.getRefType(), n.getRefId(), n.isRead(), n.getCreatedAt().toString());
        }
    }

    /** GET /api/notifications 응답: 목록 + 안 읽은 수 */
    public record NotificationList(List<NotificationView> notifications, long unread) {
    }

    /** GET /api/notifications/unread-count 응답 */
    public record UnreadCount(long unread) {
    }

    /** POST /api/devices 요청 본문 */
    public record DeviceRequest(
            @NotBlank(message = "기기 토큰이 필요합니다.") @Size(max = 300) String token,
            @Size(max = 20) String platform) {
    }

    /**
     * 알림 설정(계정정보 > 알림 설정, V14). JSON 예: {"like":true,"chat":false,"exchange":true,"pushReady":false}
     * pushReady: 서버에 Firebase 서비스 계정이 설정돼 앱이 꺼져 있을 때도 푸시(FCM/APNs)를 보낼 수 있는지
     */
    public record NotificationSettings(boolean like, boolean chat, boolean exchange, boolean pushReady) {
    }

    /** PUT 본문: 바꿀 값만 보낸다. {"chat": false} */
    public record SettingsRequest(Boolean like, Boolean chat, Boolean exchange) {
    }
}
