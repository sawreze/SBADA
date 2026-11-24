package com.sbada;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스바다 물품 마이크로서비스 (MSA 2단계, 2026-11-25) — HTTP :8099 · gRPC :9099
 *  물건 · 사진 · AI 가격 분석 · 추천 · 찜을 맡는다. DB 는 자기 것(H2 ./data/item)
 *  앱은 메인 서버(8097)로만 요청하고, 메인 서버 게이트웨이가 /api/items/** · /graphql 등을 여기로 넘긴다.
 *  다른 서버와는 배달부(gRPC)로 묻고, 허브(Kafka)로 일어난 일을 알린다.
 */
@SpringBootApplication
@EnableScheduling
public class ItemServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItemServiceApplication.class, args);
    }
}
