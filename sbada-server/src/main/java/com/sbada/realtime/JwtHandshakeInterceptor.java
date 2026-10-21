package com.sbada.realtime;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.sbada.auth.JwtService;
import com.sbada.common.ApiException;
import com.sbada.user.AppUserRepository;

/**
 * WebSocket 연결(HTTP 101 업그레이드) 직전에 JWT를 검사한다.
 * 토큰은 ws://10.0.2.2:8097/ws?token=... 또는 Authorization: Bearer ... 로 받는다.
 * 검증에 실패하면 101 대신 401로 연결을 거절한다.
 *
 * [프로그래밍 설명]
 *  - HandshakeInterceptor: 연결을 맺기 전(beforeHandshake)·후(afterHandshake)에 끼어드는 훅.
 *    beforeHandshake 가 false 를 돌려주면 연결이 거절된다.
 *  - attributes: 이 WebSocket 세션에 붙어 다니는 저장 공간(Map). 여기 넣은 userId 를
 *    SbadaWebSocketHandler 가 메시지를 받을 때마다 꺼내 '누가 보냈는지' 안다(메시지마다 토큰을 다시 보내지 않아도 됨).
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    static final String USER_ID = "userId";

    private final JwtService jwt;
    private final AppUserRepository users;

    public JwtHandshakeInterceptor(JwtService jwt, AppUserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // ① 주소의 ?token= 값을 꺼낸다(UriComponentsBuilder 로 쿼리 문자열 해석)
        String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        // ② 없으면 Authorization: Bearer 헤더에서
        if (token == null) {
            String auth = request.getHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7).trim();
            }
        }
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            // ③ REST 와 같은 JwtService 로 검증(만료·위조면 ApiException)
            long userId = jwt.verify(token);
            if (!users.existsById(userId)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            // ④ 세션 속성에 사용자 번호 저장 → 연결 허용
            attributes.put(USER_ID, userId);
            return true;
        } catch (ApiException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 할 일 없음
    }
}
