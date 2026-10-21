package com.sbada.chat;

import java.time.Instant;

import com.sbada.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅 메시지 한 개.
 *
 * [프로그래밍 설명]
 *  - @Index(room_id, id): '이 방의 메시지를 번호순으로'(가장 많이 하는 조회)를 빠르게 하는 복합 인덱스.
 *  - @Column(name = "msg_type"): 필드 이름 type 은 SQL 에서 헷갈리기 쉬워 열 이름을 바꿔 저장한다.
 *  - exchangeId: 교환 요청을 @ManyToOne 으로 연결하지 않고 번호만 둔다(필요할 때만 한 번에 모아 읽는다 — ChatService.messages).
 *  - 메시지는 만든 뒤 바뀌지 않으므로(불변) 상태를 바꾸는 메서드가 없다.
 */
@Entity
@Table(name = "chat_messages", indexes = @Index(name = "idx_msg_room", columnList = "room_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    /** TEXT: 일반 대화 · PROPOSAL: 채팅 중 '교환 제안' 카드 · SYSTEM: 성사·거절 같은 안내 */
    public enum Type { TEXT, PROPOSAL, SYSTEM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private ChatRoom room;

    /** SYSTEM 메시지는 보낸 사람이 없다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private AppUser sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "msg_type", nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 1000)
    private String content;

    private Long exchangeId;

    @Column(nullable = false)
    private Instant createdAt;

    public ChatMessage(ChatRoom room, AppUser sender, Type type, String content, Long exchangeId) {
        this.room = room;
        this.sender = sender;
        this.type = type;
        this.content = content;
        this.exchangeId = exchangeId;
        this.createdAt = Instant.now();
    }
}
