package com.sbada.chat;

import java.net.URI;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * 채팅 REST. 실시간 송수신은 WebSocket(/ws)이 맡고,
 * 이 API는 방 만들기·지난 메시지 불러오기·WebSocket 이 끊겼을 때의 보내기에 쓴다.
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 채팅 API   (기본 주소: /api/chats, 🔒 모두 로그인 필요)           │
 * └──────────────────────────────────────────────────────────────┘
 *  POST /api/chats                          채팅방 열기(물건 상세 '채팅하기') → 201
 *  GET  /api/chats                          내 채팅방 목록(최근 대화순)
 *  GET  /api/chats/{roomId}                 채팅방 하나
 *  GET  /api/chats/{roomId}/messages        메시지 목록(?afterId= 로 놓친 것만)
 *  POST /api/chats/{roomId}/messages        메시지 보내기(REST 예비 경로) → 201
 *  POST /api/chats/{roomId}/read            여기까지 읽음 처리
 *
 * [프로그래밍 설명 — REST 와 WebSocket 을 함께 쓰는 이유]
 *  - REST(HTTP)는 '요청해야 응답'이 오는 방식이라, 상대가 보낸 메시지를 바로 받으려면 계속 물어봐야 한다(폴링).
 *  - WebSocket 은 연결을 열어 둔 채 서버가 먼저 보낼 수 있어 실시간 채팅에 맞다(realtime 패키지).
 *  - 그래서: 지난 기록 조회·방 만들기 = REST / 새 메시지 주고받기 = WebSocket(끊겼으면 REST 로 보내기)
 *  - 보내기는 어느 길로 오든 ChatService.send() 하나로 처리 → 규칙(1~1000자, 참여자만)이 한 곳에 있다.
 *  - 참여자 검사: 방의 owner(물건 주인) 또는 partner(채팅을 건 사람)만 볼 수 있다 → 아니면 403 NOT_ROOM_MEMBER.
 */
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    /**
     * ■ 채팅방 열기   POST /api/chats
     *   요청 본문: {"itemId": 5}   ← 채팅하고 싶은 '남의' 물건
     *   같은 물건 + 같은 사람이면 새로 만들지 않고 기존 방을 돌려준다(방 = 물건 1개 × 관심 있는 사람 1명).
     *   처음 여는 방이면 그 물건 종류에 대한 선호도 +0.1 학습.
     *
     * 응답 201 — ChatDtos.RoomView (Location: /api/chats/{roomId})
     *   {
     *     "id": 4,
     *     "item": {"id":5,"title":"레고","price":140000,"imageUrl":"/api/images/5", …},
     *     "partner": {"id":4,"nickname":"도현","region":"마포구 서교동"},   // 나 말고 상대방
     *     "iAmOwner": false,                 // 내가 물건 주인인가
     *     "lastMessage": "박스 구성품도 다 있어요", "lastMessageAt": "2026-10-21T04:10:00Z",
     *     "unread": 2                        // 내가 안 읽은 상대 메시지 수
     *   }
     * 오류: 400 VALIDATION_FAILED(itemId 없음) / SELF_CHAT(내 물건), 404 ITEM_NOT_FOUND
     */
    @PostMapping
    public ResponseEntity<ChatDtos.RoomView> open(@Valid @RequestBody ChatDtos.OpenRoomRequest input,
                                                  Authentication auth) {
        ChatDtos.RoomView room = service.open(input.itemId(), (Long) auth.getPrincipal());
        return ResponseEntity.created(URI.create("/api/chats/" + room.id())).body(room);
    }

    /**
     * ■ 내 채팅방 목록   GET /api/chats
     * 응답 200 — {"rooms": [RoomView…]}  내가 주인이거나 상대인 방, 마지막 대화가 최근인 순
     * (앱의 채팅 탭. 필터 '안 읽음 / 내 물건 / 상대 물건' 은 unread·iAmOwner 로 앱에서 거른다)
     */
    @GetMapping
    public ChatDtos.RoomList rooms(Authentication auth) {
        return service.myRooms((Long) auth.getPrincipal());
    }

    /**
     * ■ 채팅방 하나   GET /api/chats/{roomId}
     * 응답 200 — RoomView   오류: 403 NOT_ROOM_MEMBER, 404 ROOM_NOT_FOUND
     */
    @GetMapping("/{roomId}")
    public ChatDtos.RoomView room(@PathVariable long roomId, Authentication auth) {
        return service.room(roomId, (Long) auth.getPrincipal());
    }

    /**
     * ■ 메시지 목록   GET /api/chats/{roomId}/messages            → 최근 100개(오래된 것부터)
     *                 GET /api/chats/{roomId}/messages?afterId=57 → 57번보다 새 메시지만(최대 200개)
     *   afterId 는 WebSocket 이 끊겼다 다시 연결됐을 때 '마지막으로 받은 메시지 다음부터' 채우는 데 쓴다.
     *   @RequestParam(required = false) Long afterId: 주소에 없으면 null(그래서 long 이 아니라 Long).
     *
     * 응답 200 — {"messages": [MessageView…]}
     *   MessageView 예:
     *   {"id":58,"roomId":4,"senderId":2,"senderNickname":"준호","type":"TEXT",
     *    "content":"토요일 망원역 어때요?","exchange":null,"createdAt":"2026-10-21T04:12:00Z"}
     *   type: TEXT(일반) / PROPOSAL(교환 제안 카드 — exchange 에 ExchangeView 가 들어감) / SYSTEM(안내, senderId=null)
     * 오류: 403 NOT_ROOM_MEMBER, 404 ROOM_NOT_FOUND
     */
    @GetMapping("/{roomId}/messages")
    public ChatDtos.MessageList messages(@PathVariable long roomId,
                                         @RequestParam(required = false) Long afterId,
                                         Authentication auth) {
        return service.messages(roomId, (Long) auth.getPrincipal(), afterId);
    }

    /**
     * ■ 메시지 보내기(REST)   POST /api/chats/{roomId}/messages
     *   요청 본문: {"content": "안녕하세요"}   (1~1000자)
     *   평소에는 WebSocket 으로 보내고, 연결이 끊겼을 때 앱이 이 주소로 대신 보낸다.
     *   저장 후 두 사람의 WebSocket 으로 'chat.message' 이벤트가 나가고, 상대가 접속해 있지 않으면 FCM 푸시.
     *
     * 응답 201 — 저장된 MessageView   (ResponseEntity.status(201).body(…) 로 상태를 직접 지정)
     * 오류: 400 VALIDATION_FAILED / INVALID_MESSAGE, 403 NOT_ROOM_MEMBER, 404 ROOM_NOT_FOUND
     */
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<ChatDtos.MessageView> send(@PathVariable long roomId,
                                                     @Valid @RequestBody ChatDtos.SendRequest input,
                                                     Authentication auth) {
        ChatDtos.MessageView view = service.send(roomId, (Long) auth.getPrincipal(), input.content(), null);
        return ResponseEntity.status(201).body(view);
    }

    /**
     * ■ 읽음 처리   POST /api/chats/{roomId}/read   (본문 없음)
     *   이 방의 마지막 메시지까지 읽었다고 기록 → 목록의 unread 가 0 이 되고,
     *   상대에게 'chat.read' 실시간 이벤트가 간다.
     * 응답 200 — {"ok": true}   오류: 403 NOT_ROOM_MEMBER, 404 ROOM_NOT_FOUND
     */
    @PostMapping("/{roomId}/read")
    public Map<String, Boolean> read(@PathVariable long roomId, Authentication auth) {
        service.markRead(roomId, (Long) auth.getPrincipal());
        return Map.of("ok", true);
    }
}
