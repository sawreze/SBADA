package com.sbada.realtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.sbada.chat.ChatService;
import com.sbada.common.ApiException;
import com.sbada.common.Json;

/**
 * WebSocket 메시지 처리(docs/realtime.md 의 프로토콜).
 *
 * 앱 → 서버
 *   {"type":"ping"}
 *   {"type":"chat.send", "roomId":3, "content":"안녕하세요", "clientMsgId":"a1"}
 *   {"type":"chat.read", "roomId":3}
 * 서버 → 앱
 *   {"type":"hello", "userId":1}               연결 직후
 *   {"type":"pong"}
 *   {"type":"chat.message", "message":{...}, "clientMsgId":"a1"}
 *   {"type":"chat.read", "roomId":3, "userId":2, "lastReadId":40}
 *   {"type":"notification", "notification":{...}}
 *   {"type":"exchange.updated", "exchange":{...}}
 *   {"type":"error", "code":"NOT_ROOM_MEMBER", "message":"...", "clientMsgId":"a1"}
 *
 * [프로그래밍 설명]
 *  - TextWebSocketHandler 를 상속하고, 연결 생명주기마다 스프링이 부르는 메서드를 재정의(@Override)한다:
 *      afterConnectionEstablished → 연결됨 / handleTextMessage → 메시지 받음 / afterConnectionClosed → 끊김
 *  - 메시지는 모두 {"type": …} 이 있는 JSON 문자열이고, type 값으로 switch 해서 처리한다(간단한 자체 프로토콜).
 *  - REST 와 달리 GlobalExceptionHandler 가 없으므로 예외를 직접 잡아 {"type":"error", …} 메시지로 답한다.
 *  - switch 의 '->' 문법(Java 14+): break 없이 한 줄씩 실행되는 새 switch.
 *  - 앱은 25초마다 ping 을 보내 연결이 살아 있는지 확인하고, 끊기면 1초→30초로 간격을 늘리며 다시 연결한다.
 */
@Component
public class SbadaWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SbadaWebSocketHandler.class);

    private final RealtimeHub hub;
    private final ChatService chat;

    public SbadaWebSocketHandler(RealtimeHub hub, ChatService chat) {
        this.hub = hub;
        this.chat = chat;
    }

    /** 연결 성공: 접속자 목록에 등록하고 {"type":"hello","userId":…} 로 인사 */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        long userId = userId(session);
        hub.register(userId, session);
        hub.reply(session, Map.of("type", "hello", "userId", userId));
    }

    /** 앱이 보낸 메시지 한 개 처리 */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        long userId = userId(session); // 핸드셰이크 때 저장해 둔 '보낸 사람'
        Map<String, Object> msg;
        try {
            msg = Json.readMap(message.getPayload()); // payload = 받은 문자열
        } catch (RuntimeException e) {
            error(session, "INVALID_JSON", "JSON 형식이 아닙니다.", null);
            return;
        }
        String type = String.valueOf(msg.get("type"));
        String clientMsgId = msg.get("clientMsgId") == null ? null : msg.get("clientMsgId").toString();
        try {
            switch (type) {
                case "ping" -> hub.reply(session, Map.of("type", "pong"));
                // 채팅 보내기: REST 와 같은 ChatService.send 사용 → 저장 후 두 사람에게 chat.message 이벤트
                case "chat.send" -> chat.send(longOf(msg.get("roomId")), userId,
                        msg.get("content") == null ? null : msg.get("content").toString(), clientMsgId);
                case "chat.read" -> chat.markRead(longOf(msg.get("roomId")), userId);
                default -> error(session, "UNKNOWN_TYPE", "알 수 없는 메시지 종류입니다: " + type, clientMsgId);
            }
        } catch (ApiException e) {
            // 업무 오류(참여자 아님, 빈 메시지 등) → 같은 code 로 error 이벤트
            error(session, e.getCode(), e.getMessage(), clientMsgId);
        } catch (RuntimeException e) {
            log.warn("WebSocket 처리 오류", e);
            error(session, "INTERNAL_ERROR", "서버에서 처리하지 못했습니다.", clientMsgId);
        }
    }

    /** 연결 끊김(앱 종료·네트워크 끊김): 접속자 목록에서 제거 */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object uid = session.getAttributes().get(JwtHandshakeInterceptor.USER_ID);
        if (uid instanceof Long id) {
            hub.unregister(id, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket 전송 오류 session={}: {}", session.getId(), exception.getMessage());
    }

    /** {"type":"error","code":…,"message":…,"clientMsgId":…} 를 보낸 세션에만 답장 */
    private void error(WebSocketSession session, String code, String message, String clientMsgId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "error");
        event.put("code", code);
        event.put("message", message);
        if (clientMsgId != null) {
            event.put("clientMsgId", clientMsgId);
        }
        hub.reply(session, event);
    }

    private static long userId(WebSocketSession session) {
        return (Long) session.getAttributes().get(JwtHandshakeInterceptor.USER_ID);
    }

    /** JSON 숫자는 Integer·Long 등 여러 타입으로 올 수 있고 문자열 "3" 일 수도 있어 모두 long 으로 바꾼다 */
    private static long longOf(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_ROOM", "roomId 가 올바르지 않습니다.");
        }
    }
}
