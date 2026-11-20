package com.sbada.payment.events;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.payment.common.Json;

/**
 * 아웃박스 릴레이: 아직 못 보낸 이벤트를 주기적으로 메인 서버에 보낸다.
 * 적어도 한 번 전달(at-least-once) — 같은 이벤트가 두 번 갈 수 있으므로 메인 서버가 eventId 로 중복을 거른다.
 * 운영 규모가 커지면 이 자리를 Kafka · RabbitMQ 프로듀서로 바꾸면 된다(서비스 코드는 그대로).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final CoreClient core;

    public OutboxRelay(OutboxRepository outbox, CoreClient core) {
        this.outbox = outbox;
        this.core = core;
    }

    @Scheduled(fixedDelayString = "${sbada.events.relay-delay-ms:1500}")
    @Transactional
    public void relay() {
        for (OutboxEvent e : outbox.findTop20BySentAtIsNullOrderByIdAsc()) {
            Map<String, Object> body = new LinkedHashMap<>(Json.readMap(e.getPayload()));
            body.put("eventId", e.getEventId());
            body.put("type", e.getEventType());
            body.put("userId", e.getUserId());
            if (core.sendEvent(Json.write(body))) {
                e.markSent();
            } else {
                e.markFailed();
                if (e.getAttempts() == 1) {
                    log.warn("메인 서버로 이벤트를 보내지 못함(나중에 다시 보냄): {}", e.getEventType());
                }
                break; // 순서를 지키기 위해 앞 이벤트가 실패하면 뒤 이벤트도 다음 주기로 미룬다
            }
        }
    }
}
