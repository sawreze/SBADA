package com.sbada.payment.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.payment.common.Json;

/**
 * 아웃박스 릴레이: 아직 못 보낸 이벤트를 주기적으로 보낸다.
 * 2026-11-25 MSA 2단계: 허브(Kafka 토픽 sbada.payment.events, 키 = 회원 번호)로 보낸다. 메인 서버가 듣고 알림 · 교환 결제 상태를 바꾼다.
 *  sbada.events.transport=http 이면 예전처럼 메인 서버 /internal/events 로 직접 보낸다(Kafka 없이 시연할 때).
 * 적어도 한 번 전달(at-least-once) — 같은 이벤트가 두 번 갈 수 있으므로 메인 서버가 eventId 로 중복을 거른다.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    public static final String TOPIC = "sbada.payment.events";

    private final OutboxRepository outbox;
    private final CoreClient core;
    private final KafkaTemplate<String, String> kafka;
    private final boolean useHttp;

    public OutboxRelay(OutboxRepository outbox, CoreClient core, KafkaTemplate<String, String> kafka,
                       @Value("${sbada.events.transport:kafka}") String transport) {
        this.outbox = outbox;
        this.core = core;
        this.kafka = kafka;
        this.useHttp = "http".equalsIgnoreCase(transport);
    }

    private boolean send(OutboxEvent e, String json) {
        if (useHttp) {
            return core.sendEvent(json);
        }
        try {
            kafka.send(TOPIC, String.valueOf(e.getUserId()), json).get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${sbada.events.relay-delay-ms:1500}")
    @Transactional
    public void relay() {
        for (OutboxEvent e : outbox.findTop20BySentAtIsNullOrderByIdAsc()) {
            Map<String, Object> body = new LinkedHashMap<>(Json.readMap(e.getPayload()));
            body.put("eventId", e.getEventId());
            body.put("type", e.getEventType());
            body.put("userId", e.getUserId());
            if (send(e, Json.write(body))) {
                e.markSent();
            } else {
                e.markFailed();
                if (e.getAttempts() == 1) {
                    log.warn("이벤트를 보내지 못함({} — 나중에 다시 보냄): {}", useHttp ? "메인 서버 HTTP" : "Kafka", e.getEventType());
                }
                break; // 순서를 지키기 위해 앞 이벤트가 실패하면 뒤 이벤트도 다음 주기로 미룬다
            }
        }
    }
}
