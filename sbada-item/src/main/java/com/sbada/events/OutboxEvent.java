package com.sbada.events;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 아웃박스: 허브(Kafka)로 보낼 이벤트를 먼저 같은 DB 트랜잭션 안에 적어 둔다.
 * 물건 저장과 이벤트 기록이 함께 커밋되거나 함께 취소된다 → "DB 는 바뀌었는데 이벤트는 사라짐"이 없다.
 * OutboxRelay 가 1초마다 아직 안 보낸 것을 Kafka 로 보내고 sent_at 을 채운다(Kafka 가 꺼져 있으면 다음에 다시).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(nullable = false, length = 60)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 40)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventId, String topic, String eventKey, String eventType, String payload) {
        this.eventId = eventId;
        this.topic = topic;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    void markSent() {
        this.sentAt = Instant.now();
    }

    void failed() {
        this.attempts++;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getEventKey() { return eventKey; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
