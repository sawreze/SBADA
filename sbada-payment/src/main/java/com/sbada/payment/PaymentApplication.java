package com.sbada.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스바다 결제 마이크로서비스 (포트 8098)
 *
 * MSA 에서 이 서비스가 맡는 일
 *  - 지갑 잔액 · 거래 원장 · 출금 계좌(암호화 저장) · 추가 요금 결제(수수료 5%)
 *  - 자기 DB(./data/payment)만 쓴다. 회원 · 물건 · 교환 데이터는 메인 서버가 가진다.
 *  - 로그인은 메인 서버가 발급한 JWT 를 검증만 한다.
 *  - 알림은 직접 보내지 않고 이벤트(아웃박스)로 메인 서버에 넘긴다.
 *
 * 시연용: 실제 PG · 은행과 연결하지 않는다. 결제 · 출금은 이 DB 안에서 숫자만 바뀐다.
 */
@SpringBootApplication
@EnableScheduling
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
