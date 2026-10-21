package com.sbada.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.sbada.common.Json;

/**
 * 접속 중인 사용자 ↔ WebSocket 세션 목록.
 * 한 사용자가 여러 기기(에뮬레이터 2대 등)로 접속할 수 있으므로 세션을 여러 개 둔다.
 * 여러 스레드가 같은 세션에 동시에 보내도 안전하도록 ConcurrentWebSocketSessionDecorator 로 감싼다.
 *
 * [프로그래밍 설명]
 *  - 자료 구조: { 사용자번호 → { 세션ID → 세션 } }  (Map 안의 Map)
 *  - 스프링 빈은 기본이 '싱글톤'(서버에 객체 1개)이라 모든 요청 스레드가 이 Map 을 함께 쓴다
 *    → 일반 HashMap 은 동시에 고치면 깨질 수 있어 ConcurrentHashMap(스레드 안전)을 쓴다.
 *  - 이 정보는 메모리에만 있다(서버를 다시 켜면 비고, 앱이 자동으로 다시 연결한다).
 *  - 다른 서비스(채팅·교환·알림)는 sendToUser(사용자번호, 이벤트Map) 만 부르면 되고,
 *    그 사람이 어떤 기기로 몇 개 접속했는지는 몰라도 된다(캡슐화).
 */
@Component
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    static final String OUT = "sbada.out";

    private final Map<Long, Map<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();

    /**
     * 연결되면 등록. 원래 세션을 '동시 전송에 안전한 래퍼'로 감싼다:
     * 보내기 제한 시간 5초, 보내기 대기 버퍼 512KB(느린 클라이언트 때문에 서버가 멈추지 않게).
     */
    public void register(long userId, WebSocketSession raw) {
        WebSocketSession out = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 512 * 1024);
        raw.getAttributes().put(OUT, out);
        sessions.computeIfAbsent(userId, k -> new ConcurrentHashMap<>()).put(raw.getId(), out);
        log.info("WebSocket 접속 user={} session={} (접속자 {}명)", userId, raw.getId(), sessions.size());
    }

    /** 연결이 끊기면 제거. 그 사람의 마지막 세션이었으면 사용자 칸도 지운다(→ isOnline=false → FCM 대상) */
    public void unregister(long userId, WebSocketSession raw) {
        Map<String, WebSocketSession> mine = sessions.get(userId);
        if (mine != null) {
            mine.remove(raw.getId());
            if (mine.isEmpty()) {
                sessions.remove(userId, mine);
            }
        }
    }

    /** 앱이 켜져 있어 WebSocket이 연결된 상태인가 → 아니면 FCM 푸시로 보낸다 */
    public boolean isOnline(long userId) {
        Map<String, WebSocketSession> mine = sessions.get(userId);
        return mine != null && !mine.isEmpty();
    }

    /** 접속 중인 사용자 수(GET /api/health 의 websocketOnlineUsers) */
    public int onlineUsers() {
        return sessions.size();
    }

    /** 한 사용자의 모든 연결에 이벤트(Map → JSON 문자열)를 보낸다 */
    public void sendToUser(long userId, Map<String, Object> event) {
        Map<String, WebSocketSession> mine = sessions.get(userId);
        if (mine == null || mine.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(Json.write(event));
        for (WebSocketSession s : mine.values()) {
            send(s, message);
        }
    }

    /** 요청을 보낸 그 세션에만 답한다(pong, error) */
    public void reply(WebSocketSession raw, Map<String, Object> event) {
        Object out = raw.getAttributes().get(OUT);
        send(out instanceof WebSocketSession ws ? ws : raw, new TextMessage(Json.write(event)));
    }

    /** 실제 전송. 끊긴 세션이거나 실패해도 예외를 밖으로 던지지 않는다(한 사람 실패가 다른 처리를 막지 않게) */
    private void send(WebSocketSession s, TextMessage message) {
        if (!s.isOpen()) {
            return;
        }
        try {
            s.sendMessage(message);
        } catch (IOException | RuntimeException e) {
            log.debug("WebSocket 전송 실패 session={}: {}", s.getId(), e.getMessage());
        }
    }
}
