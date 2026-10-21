package com.sbada.notification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.AfterCommit;
import com.sbada.common.ApiException;
import com.sbada.realtime.RealtimeHub;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;

/**
 * 알림 전달 경로(1장 '폴링·SSE·WebSocket·푸시' 중 상황에 맞는 방식 고르기)
 *   ① DB에 저장   → 알림함에서 다시 볼 수 있다
 *   ② 접속 중이면 → WebSocket 으로 즉시 전달 → 앱이 Android 알림을 띄운다
 *   ③ 접속 안 함  → FCM 푸시(설정한 경우)
 *
 * [프로그래밍 설명]
 *  - notify() 를 부르는 쪽(ExchangeService 등)의 트랜잭션에 함께 참여한다(@Transactional 기본 전파 = REQUIRED:
 *    이미 트랜잭션이 있으면 그 안에서, 없으면 새로 시작). 그래서 교환 처리가 롤백되면 알림 저장도 함께 취소된다.
 *  - 전달(deliver)은 AfterCommit 으로 커밋 뒤에 → '저장되지 않은 알림'이 폰에 뜨는 일이 없다.
 *  - FCM 데이터(data)는 문자열 Map 이어야 해서 refId 를 toString() 으로 바꿔 넣는다.
 */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final DeviceTokenRepository devices;
    private final AppUserRepository users;
    private final RealtimeHub hub;
    private final FcmPushSender fcm;

    public NotificationService(NotificationRepository notifications, DeviceTokenRepository devices,
                               AppUserRepository users, RealtimeHub hub, FcmPushSender fcm) {
        this.notifications = notifications;
        this.devices = devices;
        this.users = users;
        this.hub = hub;
        this.fcm = fcm;
    }

    /** 알림 만들기: 저장 + (커밋 후) 실시간 또는 푸시 전달 */
    @Transactional
    public void notify(long userId, NotificationType type, String title, String body, String refType, Long refId) {
        Notification saved = notifications.save(
                new Notification(users.getReferenceById(userId), type, title, body, refType, refId));
        NotificationDtos.NotificationView view = NotificationDtos.NotificationView.from(saved);
        AfterCommit.run(() -> deliver(userId, view));
    }

    /** WebSocket 에 연결돼 있으면 즉시 전송, 아니면 FCM(설정 안 했으면 조용히 건너뜀 — 알림함에는 이미 저장됨) */
    private void deliver(long userId, NotificationDtos.NotificationView view) {
        if (hub.isOnline(userId)) {
            hub.sendToUser(userId, Map.of("type", "notification", "notification", view));
        } else {
            Map<String, String> data = new HashMap<>();
            data.put("type", view.type());
            if (view.refType() != null) {
                data.put("refType", view.refType());
            }
            if (view.refId() != null) {
                data.put("refId", view.refId().toString());
            }
            fcm.sendAsync(userId, view.title(), view.body(), data);
        }
    }

    /** 채팅처럼 알림함에 저장하지 않는 알림: 접속 중이 아니면 FCM 으로만 보낸다. */
    public void pushIfOffline(long userId, String title, String body, Map<String, String> data) {
        if (!hub.isOnline(userId)) {
            fcm.sendAsync(userId, title, body, data);
        }
    }

    /** GET /api/notifications — 최근 100개 + 안 읽은 수 */
    @Transactional(readOnly = true)
    public NotificationDtos.NotificationList list(long userId) {
        List<NotificationDtos.NotificationView> list = notifications.findRecent(userId, PageRequest.of(0, 100))
                .stream().map(NotificationDtos.NotificationView::from).toList();
        return new NotificationDtos.NotificationList(list, notifications.countUnread(userId));
    }

    @Transactional(readOnly = true)
    public long unread(long userId) {
        return notifications.countUnread(userId);
    }

    /** POST /api/notifications/{id}/read — 없으면 404, 남의 것이면 403, 맞으면 읽음(변경 감지로 UPDATE) */
    @Transactional
    public NotificationDtos.NotificationView markRead(long id, long userId) {
        Notification n = notifications.findById(id)
                .orElseThrow(() -> ApiException.notFound("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다."));
        if (n.getUser().getId() != userId) {
            throw ApiException.forbidden("NOT_MY_NOTIFICATION", "내 알림만 읽음 처리할 수 있습니다.");
        }
        n.markRead();
        return NotificationDtos.NotificationView.from(n);
    }

    /** POST /api/notifications/read-all — UPDATE 한 번으로 모두 읽음 */
    @Transactional
    public int markAllRead(long userId) {
        return notifications.markAllRead(userId);
    }

    /**
     * POST /api/devices — 토큰이 이미 있으면 주인 변경(UPDATE), 없으면 새로 저장(INSERT).
     * Optional.ifPresentOrElse(있을 때 할 일, 없을 때 할 일)
     */
    @Transactional
    public void registerDevice(long userId, String token, String platform) {
        AppUser user = users.getReferenceById(userId);
        devices.findByToken(token).ifPresentOrElse(
                d -> d.reassign(user, platform),
                () -> devices.save(new DeviceToken(user, token, platform)));
    }

    public boolean fcmEnabled() {
        return fcm.isEnabled();
    }
}
