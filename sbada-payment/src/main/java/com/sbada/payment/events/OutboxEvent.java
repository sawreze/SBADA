package com.sbada.payment.events;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 보낼 이벤트 한 건(트랜잭셔널 아웃박스). sentAt 이 null 이면 아직 못 보낸 것 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 1000)
    private String payload;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventType, long userId, String payload) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.userId = userId;
        this.payload = payload;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public void markSent() {
        this.sentAt = Instant.now();
    }

    public void markFailed() {
        this.attempts = attempts + 1;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Long getUserId() { return userId; }
    public String getPayload() { return payload; }
    public Integer getAttempts() { return attempts; }
    public Instant getSentAt() { return sentAt; }
}
