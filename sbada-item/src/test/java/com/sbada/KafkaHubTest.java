package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.events.EventPublisher;
import com.sbada.events.OutboxRelay;
import com.sbada.events.Topics;
import com.sbada.item.ItemRepository;

/**
 * 허브(Kafka) 실제 왕복 — 테스트 안에 띄운 Kafka(EmbeddedKafka) 로 (2026-11-25 MSA 2단계)
 *  ① 아웃박스에 적은 이벤트 → OutboxRelay → sbada.item.events 토픽에 도착(키 = 물건 번호)
 *  ② 메인 서버가 보낸 것처럼 sbada.exchange.events 에 EXCHANGE_REQUESTED → 물품 서버 소비자가 '교환 요청 수' +1
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-item-kafka-test;DB_CLOSE_DELAY=-1",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "sbada.events.relay-enabled=false",
        "sbada.ai.provider=fallback"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {Topics.ITEM, Topics.EXCHANGE, Topics.USER, Topics.NOTIFICATIONS})
class KafkaHubTest {

    @Autowired
    EventPublisher events;
    @Autowired
    OutboxRelay relay;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    EmbeddedKafkaBroker broker;
    @Autowired
    ItemRepository items;
    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;

    @Test
    void outboxGoesToKafkaAndExchangeEventsAreConsumed() throws Exception {
        // ① 아웃박스 → Kafka
        tx.executeWithoutResult(s -> events.publish(Topics.ITEM, 4242, "ITEM_CREATED", Map.of("itemId", 4242)));
        assertThat(relay.relayNow()).isGreaterThanOrEqualTo(1);
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "hub-test", true);
        props.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, Topics.ITEM);
            ConsumerRecord<String, String> rec = KafkaTestUtils.getSingleRecord(consumer, Topics.ITEM, Duration.ofSeconds(10));
            assertThat(rec.key()).isEqualTo("4242");
            assertThat(rec.value()).contains("ITEM_CREATED").contains("\"source\":\"sbada-item\"");
        }

        // ② 메인 서버가 보낸 교환 요청 이벤트 → 물품 서버가 듣고 요청 수 +1
        TestSupport.Account owner = TestSupport.newAccount(mvc, "kafka");
        long itemId = TestSupport.createItem(mvc, owner, "카프카 자전거", "SPORTS", "GOOD", "[]");
        kafka.send(Topics.EXCHANGE, String.valueOf(itemId), """
                {"eventId":"kafka-ex-%d","type":"EXCHANGE_REQUESTED","source":"sbada-server","exchangeId":1,
                 "offeredItemId":2,"targetItemId":%d}
                """.formatted(itemId, itemId)).get();
        int count = 0;
        for (int i = 0; i < 100 && count == 0; i++) {
            Thread.sleep(100);
            count = tx.execute(s -> items.findById(itemId).orElseThrow().getRequestCount());
        }
        assertThat(count).isEqualTo(1);
    }
}
