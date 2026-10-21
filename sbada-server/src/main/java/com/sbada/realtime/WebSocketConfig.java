package com.sbada.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * ws://127.0.0.1:8097/ws (에뮬레이터: ws://10.0.2.2:8097/ws)
 *
 * ■ 실시간 연결 주소   ws://localhost:8097/ws?token={accessToken}
 *
 * [프로그래밍 설명 — WebSocket]
 *  - HTTP 요청으로 시작해서 "Upgrade: websocket" 헤더로 프로토콜을 바꾸면(HTTP 101 Switching Protocols)
 *    그 TCP 연결을 계속 열어 둔 채 서버·앱이 서로 아무 때나 메시지를 보낼 수 있다(양방향).
 *  - @EnableWebSocket + WebSocketConfigurer: Spring 의 순수 WebSocket 지원을 켜고 주소별 처리기를 등록한다.
 *      handler     = 메시지를 처리하는 클래스(SbadaWebSocketHandler)
 *      interceptor = 연결(핸드셰이크) 직전에 JWT 를 검사하는 클래스(JwtHandshakeInterceptor)
 *  - SecurityConfig 에서 /ws 는 permitAll 이다. 브라우저 WebSocket 은 Authorization 헤더를 붙이기 어려워서
 *    토큰을 주소의 ?token= 으로 받고, 검사는 interceptor 가 대신 한다.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SbadaWebSocketHandler handler;
    private final JwtHandshakeInterceptor interceptor;

    public WebSocketConfig(SbadaWebSocketHandler handler, JwtHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 모바일 앱은 브라우저가 아니라 Origin 헤더를 보내지 않는 경우가 많다. 인증은 JWT 로 한다.
        registry.addHandler(handler, "/ws")
                .addInterceptors(interceptor)
                .setAllowedOriginPatterns("*");
    }
}
