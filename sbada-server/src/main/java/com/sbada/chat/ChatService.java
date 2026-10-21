package com.sbada.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.AfterCommit;
import com.sbada.common.ApiException;
import com.sbada.exchange.ExchangeDtos;
import com.sbada.exchange.ExchangeRepository;
import com.sbada.exchange.ExchangeRequest;
import com.sbada.item.Item;
import com.sbada.item.ItemDtos;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;
import com.sbada.notification.NotificationService;
import com.sbada.realtime.RealtimeHub;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;
import com.sbada.user.PreferenceService;
import com.sbada.user.UserDtos;

/**
 * 채팅. 메시지는 먼저 DB에 저장하고(유실 방지), 커밋 뒤 두 참여자의 WebSocket 으로 보낸다.
 * 상대가 접속해 있지 않으면 FCM 푸시로 알린다.
 * REST(POST /api/chats/{id}/messages)와 WebSocket(chat.send) 두 길 모두 이 send() 를 쓴다.
 *
 * [프로그래밍 설명 — 메시지 한 개가 상대에게 가는 길]
 *   보내기(REST 또는 WebSocket) → send()
 *     ① 검사(1~1000자, 방 참여자) → ② chat_messages INSERT → ③ 방의 미리보기·읽음 갱신
 *     ④ 커밋 후: RealtimeHub 가 두 사람의 열린 WebSocket 에 {"type":"chat.message", "message":{…}} 전송
 *     ⑤ 커밋 후: 상대가 WebSocket 에 없으면 NotificationService.pushIfOffline → FCM 푸시
 *  - '저장 먼저, 전송은 커밋 후' 순서라서 전송이 실패해도 메시지는 DB 에 남고, 앱이 afterId 로 다시 가져올 수 있다.
 */
@Service
public class ChatService {

    private final ChatRoomRepository rooms;
    private final ChatMessageRepository messages;
    private final ItemRepository items;
    private final AppUserRepository users;
    private final ExchangeRepository exchanges;
    private final RealtimeHub hub;
    private final NotificationService notifications;
    private final PreferenceService preferences;

    public ChatService(ChatRoomRepository rooms, ChatMessageRepository messages, ItemRepository items,
                       AppUserRepository users, ExchangeRepository exchanges, RealtimeHub hub,
                       NotificationService notifications, PreferenceService preferences) {
        this.rooms = rooms;
        this.messages = messages;
        this.items = items;
        this.users = users;
        this.exchanges = exchanges;
        this.hub = hub;
        this.notifications = notifications;
        this.preferences = preferences;
    }

    /** 물건 상세의 '채팅하기'. 같은 물건·같은 사람이면 기존 방을 돌려준다. (POST /api/chats) */
    @Transactional
    public ChatDtos.RoomView open(long itemId, long userId) {
        Item item = items.findById(itemId)
                .filter(i -> i.getStatus() != ItemStatus.HIDDEN)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다."));
        if (item.isOwnedBy(userId)) {
            throw ApiException.badRequest("SELF_CHAT", "내 물건에는 채팅을 걸 수 없습니다.");
        }
        boolean isNew = rooms.findByItemAndPartner(itemId, userId).isEmpty();
        ChatRoom room = ensureRoom(item, users.getReferenceById(userId));
        if (isNew) {
            preferences.learn(userId, item.getCategory(), PreferenceService.Behavior.CHAT); // 처음 채팅 = 관심 +0.1
        }
        return toRoomView(room, userId);
    }

    /**
     * 교환 요청 등 내부에서 방이 필요할 때.
     * Optional.orElseGet(공급자): 비어 있을 때만 람다를 실행해 새 방을 저장한다(있으면 저장하지 않음).
     */
    @Transactional
    public ChatRoom ensureRoom(Item item, AppUser partner) {
        return rooms.findByItemAndPartner(item.getId(), partner.getId())
                .orElseGet(() -> rooms.save(new ChatRoom(item, partner)));
    }

    /**
     * 일반 메시지 보내기. clientMsgId 는 앱이 붙인 임시 번호(WebSocket 으로 보낼 때만) —
     * 응답 이벤트에 그대로 돌려줘서 앱이 '보내는 중' 말풍선을 '보냄'으로 바꿀 수 있게 한다.
     */
    @Transactional
    public ChatDtos.MessageView send(long roomId, long userId, String content, String clientMsgId) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > 1000) { // WebSocket 경로는 @Valid 가 없으므로 여기서 다시 검사
            throw ApiException.badRequest("INVALID_MESSAGE", "메시지는 1~1000자로 보내 주세요.");
        }
        ChatRoom room = requireRoom(roomId);
        room.requireMember(userId);
        AppUser sender = users.getReferenceById(userId);
        ChatMessage saved = messages.save(new ChatMessage(room, sender, ChatMessage.Type.TEXT, text, null));
        room.touch(text);
        room.markRead(userId, saved.getId()); // 내가 보낸 메시지까지는 당연히 읽은 것
        ChatDtos.MessageView view = toMessageView(saved, Map.of(), userId);
        broadcast(room, view, clientMsgId);

        // 람다 안에서 쓸 값은 미리 지역 변수로 꺼내 둔다(커밋 후에는 트랜잭션·엔티티를 안전하게 쓸 수 없으므로)
        AppUser other = room.other(userId);
        long otherId = other.getId();
        String nickname = sender.getNickname();
        AfterCommit.run(() -> notifications.pushIfOffline(otherId, nickname, text,
                Map.of("type", "CHAT_MESSAGE", "refType", "CHAT", "refId", Long.toString(roomId))));
        return view;
    }

    /** 교환 제안 카드·성사 안내 같은 메시지를 방에 남긴다. (ExchangeService 가 부름, sender=null 이면 SYSTEM) */
    @Transactional
    public void post(ChatRoom room, AppUser sender, ChatMessage.Type type, String content, ExchangeRequest exchange) {
        ChatMessage saved = messages.save(new ChatMessage(room, sender, type, content,
                exchange == null ? null : exchange.getId()));
        room.touch(content);
        if (sender != null) {
            room.markRead(sender.getId(), saved.getId());
        }
        Map<Long, ExchangeRequest> ex = exchange == null ? Map.of() : Map.of(exchange.getId(), exchange);
        broadcast(room, toMessageView(saved, ex, null), null);
    }

    /**
     * 두 참여자에게 실시간 이벤트를 보낸다(커밋 후).
     * 이벤트 JSON: {"type":"chat.message","message":{MessageView…},"clientMsgId":"…"(있을 때만)}
     * 같은 사람이 여러 기기로 접속해 있으면 RealtimeHub 가 모든 연결에 보낸다.
     */
    private void broadcast(ChatRoom room, ChatDtos.MessageView view, String clientMsgId) {
        long ownerId = room.getOwner().getId();
        long partnerId = room.getPartner().getId();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "chat.message");
        event.put("message", view);
        if (clientMsgId != null) {
            event.put("clientMsgId", clientMsgId);
        }
        AfterCommit.run(() -> {
            hub.sendToUser(ownerId, event);
            hub.sendToUser(partnerId, event);
        });
    }

    /** POST /api/chats/{roomId}/read — 마지막 메시지까지 읽음 + 상대에게 {"type":"chat.read",…} */
    @Transactional
    public void markRead(long roomId, long userId) {
        ChatRoom room = requireRoom(roomId);
        room.requireMember(userId);
        Long max = messages.maxId(roomId);
        if (max == null) { // 메시지가 하나도 없는 방
            return;
        }
        room.markRead(userId, max);
        long otherId = room.other(userId).getId();
        AfterCommit.run(() -> hub.sendToUser(otherId,
                Map.of("type", "chat.read", "roomId", roomId, "userId", userId, "lastReadId", max)));
    }

    /** GET /api/chats */
    @Transactional(readOnly = true)
    public ChatDtos.RoomList myRooms(long userId) {
        List<ChatDtos.RoomView> list = rooms.findMine(userId).stream().map(r -> toRoomView(r, userId)).toList();
        return new ChatDtos.RoomList(list);
    }

    /** GET /api/chats/{roomId} */
    @Transactional(readOnly = true)
    public ChatDtos.RoomView room(long roomId, long userId) {
        ChatRoom room = requireRoom(roomId);
        room.requireMember(userId);
        return toRoomView(room, userId);
    }

    /** afterId 가 있으면 그 뒤만, 없으면 최근 100개 (GET /api/chats/{roomId}/messages) */
    @Transactional(readOnly = true)
    public ChatDtos.MessageList messages(long roomId, long userId, Long afterId) {
        ChatRoom room = requireRoom(roomId);
        room.requireMember(userId);
        List<ChatMessage> list;
        if (afterId != null) {
            list = messages.findAfter(roomId, afterId, PageRequest.of(0, 200));
        } else {
            // 최신 100개를 내림차순으로 가져온 뒤 뒤집어 '오래된 것 → 최신' 순서로 만든다(채팅 화면 순서)
            list = new ArrayList<>(messages.findLatest(roomId, PageRequest.of(0, 100)));
            Collections.reverse(list);
        }
        // 제안 카드에 붙일 교환 요청들을 '한 번의 쿼리'로 모아 읽는다(N+1 문제 방지)
        Set<Long> exchangeIds = list.stream().map(ChatMessage::getExchangeId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ExchangeRequest> ex = new HashMap<>();
        if (!exchangeIds.isEmpty()) {
            for (ExchangeRequest e : exchanges.findAllById(exchangeIds)) {
                ex.put(e.getId(), e);
            }
        }
        return new ChatDtos.MessageList(list.stream().map(m -> toMessageView(m, ex, userId)).toList());
    }

    private ChatRoom requireRoom(long roomId) {
        return rooms.findById(roomId)
                .orElseThrow(() -> ApiException.notFound("ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다."));
    }

    /** 엔티티 → RoomView. 안 읽은 수는 매번 계산한다(lastReadId 뒤의 상대 메시지 수). */
    private ChatDtos.RoomView toRoomView(ChatRoom r, long userId) {
        long unread = messages.countUnread(r.getId(), userId, r.lastReadIdOf(userId));
        return new ChatDtos.RoomView(r.getId(), ItemDtos.ItemSummary.from(r.getItem()),
                UserDtos.PublicUser.from(r.other(userId)), r.getOwner().getId() == userId,
                r.getLastMessagePreview(), r.getLastMessageAt().toString(), unread);
    }

    /** 엔티티 → MessageView. 교환 요청이 연결된 메시지면 ExchangeView 를 함께 넣는다. */
    private ChatDtos.MessageView toMessageView(ChatMessage m, Map<Long, ExchangeRequest> exchangesById, Long viewerId) {
        ExchangeDtos.ExchangeView ex = null;
        if (m.getExchangeId() != null) {
            ExchangeRequest e = exchangesById.get(m.getExchangeId());
            if (e != null) {
                ex = ExchangeDtos.ExchangeView.from(e, viewerId);
            }
        }
        AppUser sender = m.getSender();
        return new ChatDtos.MessageView(m.getId(), m.getRoom().getId(),
                sender == null ? null : sender.getId(), sender == null ? null : sender.getNickname(),
                m.getType().name(), m.getContent(), ex, m.getCreatedAt().toString());
    }
}
