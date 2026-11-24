package com.sbada.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sbada.common.Json;

/**
 * 이벤트 내기 = 아웃박스에 한 줄 적기(지금 열린 트랜잭션 안에서). 실제 전송은 OutboxRelay.
 * 메시지 모양: {"eventId","type","source":"sbada-item","occurredAt", …내용}
 */
@Component
public class EventPublisher {

    public static final String SOURCE = "sbada-server";

    private final OutboxRepository outbox;

    public EventPublisher(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    public void publish(String topic, Object key, String type, Map<String, ?> data) {
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("type", type);
        body.put("source", SOURCE);
        body.put("occurredAt", Instant.now().toString());
        body.putAll(data);
        outbox.save(new OutboxEvent(eventId, topic, String.valueOf(key), type, Json.write(body)));
    }

    /**
     * 알림 한 건을 메인 서버(알림 서비스)에 맡긴다. notificationType 은 메인 서버의 NotificationType 이름
     * (ITEM_LIKED · ITEM_HELD · MATCH_SUGGESTED …). refType · refId 는 앱이 눌렀을 때 열 화면.
     */
    public void notify(long userId, String notificationType, String title, String body, String refType, Long refId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("notificationType", notificationType);
        data.put("title", title);
        data.put("body", body);
        data.put("refType", refType);
        data.put("refId", refId);
        publish(Topics.NOTIFICATIONS, userId, "NOTIFY", data);
    }
}
