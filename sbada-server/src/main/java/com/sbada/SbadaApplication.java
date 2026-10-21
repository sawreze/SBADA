package com.sbada;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 스바다 서버 진입점(main 메서드가 있는 클래스).
 * 3계층 구조(1장): 앱(Flutter) ⇄ 서버(Spring Boot) ⇄ DB(H2)
 *  - REST(HTTP·JSON)   : 회원·상품·교환·추천
 *  - WebSocket(/ws)    : 채팅, 실시간 알림
 *  - FCM(선택)         : 앱이 꺼져 있을 때 푸시
 *
 * [프로그래밍 설명]
 *  - @SpringBootApplication = 아래 세 가지를 한 번에 켜는 애너테이션이다.
 *      ① @Configuration      : 이 클래스도 설정(Bean 정의) 클래스로 쓴다.
 *      ② @EnableAutoConfiguration : 의존성(build.gradle)에 따라 Tomcat·JPA·Security·Flyway 등을 자동 설정한다.
 *      ③ @ComponentScan      : 이 패키지(com.sbada)와 하위 패키지에서 @Component·@Service·@RestController
 *                               ·@Repository·@Configuration 이 붙은 클래스를 찾아 '빈(Bean, 스프링이 만들어 관리하는 객체)'으로 등록한다.
 *  - 그래서 이 파일보다 아래 패키지(auth, item, chat …)에 있는 클래스만 자동으로 등록된다.
 *  - 패키지 구조는 '기능별(도메인별)'로 나눴다: 각 패키지 안에 Controller(요청 받기) → Service(업무 규칙)
 *    → Repository(DB) → Entity(테이블과 연결된 클래스) → Dtos(요청·응답 JSON 모양) 가 함께 있다.
 */
@SpringBootApplication
public class SbadaApplication {

    /**
     * 프로그램 시작점. SpringApplication.run 이 하는 일:
     *  ① application.properties(+ application-local.properties) 설정을 읽고
     *  ② 컴포넌트 스캔으로 빈들을 만들고 생성자에 서로 넣어 주며(의존성 주입, DI)
     *  ③ Flyway 가 DB 테이블을 최신 버전으로 맞춘 뒤
     *  ④ 내장 Tomcat 웹 서버를 8097 포트로 띄워 HTTP·WebSocket 요청을 기다린다.
     */
    public static void main(String[] args) {
        SpringApplication.run(SbadaApplication.class, args);
    }
}
