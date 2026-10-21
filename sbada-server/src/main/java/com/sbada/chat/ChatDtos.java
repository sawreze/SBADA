package com.sbada.chat;

import java.util.List;

import com.sbada.exchange.ExchangeDtos;
import com.sbada.item.ItemDtos;
import com.sbada.user.UserDtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 채팅 API 의 요청/응답 JSON 모양. 예시는 ChatController 주석 참고.
 * MessageView 는 REST 응답과 WebSocket 'chat.message' 이벤트에 똑같이 쓰인다(앱이 한 가지 모양만 알면 됨).
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** POST /api/chats 요청 본문 {"itemId": 5} */
    public record OpenRoomRequest(@NotNull(message = "물건 번호(itemId)가 필요합니다.") Long itemId) {
    }

    /** POST /api/chats/{roomId}/messages 요청 본문 {"content": "…"} */
    public record SendRequest(
            @NotBlank(message = "메시지를 입력해 주세요.") @Size(max = 1000, message = "메시지는 1000자 이하입니다.")
            String content) {
    }

    /** partner = 나 말고 상대방 */
    public record RoomView(long id, ItemDtos.ItemSummary item, UserDtos.PublicUser partner, boolean iAmOwner,
                           String lastMessage, String lastMessageAt, long unread) {
    }

    /** senderId·senderNickname 은 SYSTEM 메시지면 null, exchange 는 PROPOSAL·SYSTEM 메시지에 연결된 교환 요청 */
    public record MessageView(long id, long roomId, Long senderId, String senderNickname, String type,
                              String content, ExchangeDtos.ExchangeView exchange, String createdAt) {
    }

    public record RoomList(List<RoomView> rooms) {
    }

    public record MessageList(List<MessageView> messages) {
    }
}
