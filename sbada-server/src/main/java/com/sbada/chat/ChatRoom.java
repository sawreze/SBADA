package com.sbada.chat;

import java.time.Instant;

import com.sbada.common.ApiException;
import com.sbada.item.Item;
import com.sbada.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 물건 하나 × 관심 있는 사람 한 명 = 채팅방 하나 (당근마켓 방식)
 *
 * [프로그래밍 설명]
 *  - @UniqueConstraint(item_id, partner_id): 같은 물건에 같은 사람이 방을 두 개 만들 수 없게 DB 가 막는다.
 *  - 읽음 표시: 방에 '주인이 마지막으로 읽은 메시지 번호', '상대가 마지막으로 읽은 번호' 두 칸만 둔다.
 *    메시지마다 읽음 여부를 저장하지 않아도 '이 번호보다 큰 메시지 = 안 읽음'으로 계산할 수 있다.
 *  - lastMessageAt·lastMessagePreview 를 방에 복사해 두면(비정규화) 목록 화면에서 메시지 테이블을 매번 뒤지지 않아도 된다.
 */
@Entity
@Table(name = "chat_rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "partner_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    /** 물건 주인 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    /** 채팅을 건 사람 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partner_id")
    private AppUser partner;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastMessageAt;

    @Column(length = 100)
    private String lastMessagePreview;

    private long ownerLastReadId;
    private long partnerLastReadId;

    public ChatRoom(Item item, AppUser partner) {
        this.item = item;
        this.owner = item.getOwner();
        this.partner = partner;
        this.createdAt = Instant.now();
        this.lastMessageAt = this.createdAt;
    }

    public boolean isMember(long userId) {
        return owner.getId() == userId || partner.getId() == userId;
    }

    /** 참여자가 아니면 403 — 모든 채팅 API 가 먼저 부른다 */
    public void requireMember(long userId) {
        if (!isMember(userId)) {
            throw ApiException.forbidden("NOT_ROOM_MEMBER", "이 채팅방의 참여자가 아닙니다.");
        }
    }

    /** 나 말고 상대방 */
    public AppUser other(long userId) {
        return owner.getId() == userId ? partner : owner;
    }

    public long lastReadIdOf(long userId) {
        return owner.getId() == userId ? ownerLastReadId : partnerLastReadId;
    }

    /** 읽은 번호는 앞으로만 간다(Math.max) — 늦게 도착한 옛 읽음 요청이 값을 되돌리지 않게 */
    public void markRead(long userId, long messageId) {
        if (owner.getId() == userId) {
            ownerLastReadId = Math.max(ownerLastReadId, messageId);
        } else if (partner.getId() == userId) {
            partnerLastReadId = Math.max(partnerLastReadId, messageId);
        }
    }

    /** 새 메시지가 왔을 때: 정렬용 시각과 미리보기(100자) 갱신 */
    public void touch(String preview) {
        this.lastMessageAt = Instant.now();
        this.lastMessagePreview = preview.length() > 100 ? preview.substring(0, 100) : preview;
    }
}
