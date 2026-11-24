package com.sbada.events;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 → 허브(Kafka) 중계. 1초마다 안 보낸 이벤트를 순서대로 보낸다.
 * 하나가 실패하면(Kafka 꺼짐 등) 거기서 멈추고 다음 주기에 다시 → 순서가 뒤바뀌지 않는다(최소 한 번 전달).
 * 받는 쪽은 eventId 로 중복을 거른다(processed_events).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private long lastWarn;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       @Value("${sbada.events.relay-enabled:true}") boolean enabled) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${sbada.events.relay-delay-ms:1000}")
    public void relay() {
        if (enabled) {
            relayNow();
        }
    }

    /** 보낸 개수(테스트에서 직접 부른다) */
    public int relayNow() {
        int sent = 0;
        for (OutboxEvent e : outbox.findTop100BySentAtIsNullOrderByIdAsc()) {
            try {
                kafka.send(e.getTopic(), e.getEventKey(), e.getPayload()).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                tx.executeWithoutResult(s -> outbox.findById(e.getId()).ifPresent(OutboxEvent::failed));
                if (System.currentTimeMillis() - lastWarn > 60_000) { // 1분에 한 번만 경고(로그 폭주 방지)
                    lastWarn = System.currentTimeMillis();
                    log.warn("허브(Kafka)로 이벤트를 못 보냈어요 — 아웃박스에 남겨 두고 다시 보낼게요: {}", ex.getMessage());
                }
                break;
            }
            tx.executeWithoutResult(s -> outbox.findById(e.getId()).ifPresent(OutboxEvent::markSent));
            sent++;
        }
        return sent;
    }
}
