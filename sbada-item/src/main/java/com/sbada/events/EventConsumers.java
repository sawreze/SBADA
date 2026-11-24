package com.sbada.events;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.Json;
import com.sbada.item.ItemRepository;
import com.sbada.seller.SellerDirectory;

/**
 * 허브(Kafka)에서 듣는 이벤트 (소비자 그룹 sbada-item)
 *  sbada.exchange.events  EXCHANGE_REQUESTED → 상대 물건의 '교환 요청 수' +1 (인기도 점수에 쓰임)
 *  sbada.user.events      USER_UPDATED       → 판매자 사본(닉네임 · 동네) 고치기
 * 같은 이벤트가 두 번 와도 processed_events 로 한 번만 반영한다(최소 한 번 전달 + 멱등 소비자 = 실질적으로 한 번).
 */
@Component
public class EventConsumers {

    private static final Logger log = LoggerFactory.getLogger(EventConsumers.class);

    private final ProcessedEventRepository processed;
    private final ItemRepository items;
    private final SellerDirectory sellers;
    private final TransactionTemplate tx;

    public EventConsumers(ProcessedEventRepository processed, ItemRepository items, SellerDirectory sellers,
                          TransactionTemplate tx) {
        this.processed = processed;
        this.items = items;
        this.sellers = sellers;
        this.tx = tx;
    }

    @KafkaListener(topics = Topics.EXCHANGE, groupId = "sbada-item")
    public void onExchange(String message) {
        handle(message);
    }

    @KafkaListener(topics = Topics.USER, groupId = "sbada-item")
    public void onUser(String message) {
        handle(message);
    }

    /** 메시지 한 건 처리(테스트에서 직접 부른다). 처리했으면 true, 이미 처리한 것이면 false */
    public boolean handle(String message) {
        Map<String, Object> e = Json.readMap(message);
        String eventId = String.valueOf(e.get("eventId"));
        String type = String.valueOf(e.get("type"));
        Boolean done = tx.execute(s -> {
            if (processed.existsById(eventId)) {
                return false;
            }
            processed.save(new ProcessedEvent(eventId));
            switch (type) {
                case "EXCHANGE_REQUESTED" -> {
                    long target = num(e.get("targetItemId"));
                    items.findById(target).ifPresent(i -> i.increaseRequest());
                }
                case "USER_UPDATED" -> sellers.upsert(num(e.get("userId")), str(e.get("nickname")),
                        str(e.get("region")), str(e.get("secondRegion")));
                default -> {
                    // 이 서버와 상관없는 이벤트는 건너뛴다
                }
            }
            return true;
        });
        log.debug("이벤트 {} {} → {}", type, eventId, done);
        return Boolean.TRUE.equals(done);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
