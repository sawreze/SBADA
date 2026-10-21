package com.sbada.exchange;

import java.time.Instant;

import com.sbada.common.ApiException;
import com.sbada.item.Item;
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
 * '바로 바꾸기' 요청. requester 가 offeredItem(내 물건)을 내놓고 targetItem(상대 물건)을 원한다.
 * 요청을 받은 사람(targetOwner)이 수락해야만 교환이 성사된다.
 *
 * [프로그래밍 설명 — 상태를 가진 엔티티]
 *  - 한 요청에 사람 2명(@ManyToOne AppUser 두 개)과 물건 2개(@ManyToOne Item 두 개)가 연결된다.
 *    같은 엔티티 타입을 두 번 참조하므로 @JoinColumn 으로 외래 키 열 이름을 각각 정했다.
 *  - 상태 변경은 accept()/reject()/cancel() 메서드로만 하고, 모두 close() 를 거친다.
 *    close() 가 'REQUESTED 일 때만 바꿀 수 있다'는 규칙을 한 곳에서 지켜서, 어디서 부르든 규칙이 깨지지 않는다
 *    (엔티티가 스스로 자기 상태 규칙을 지키는 설계 — '도메인 모델').
 *  - @Index 세 개: 물건별 요청 찾기, 받은 사람별 목록을 빠르게 하는 DB 인덱스.
 */
@Entity
@Table(name = "exchange_requests", indexes = {
        @Index(name = "idx_ex_target", columnList = "target_item_id, status"),
        @Index(name = "idx_ex_offered", columnList = "offered_item_id, status"),
        @Index(name = "idx_ex_owner", columnList = "target_owner_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 요청을 보낸 사람 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id")
    private AppUser requester;

    /** 요청을 받은 사람(원하는 물건의 주인) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_owner_id")
    private AppUser targetOwner;

    /** 보낸 사람이 내놓는 물건 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offered_item_id")
    private Item offeredItem;

    /** 보낸 사람이 원하는 물건 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_item_id")
    private Item targetItem;

    @Column(length = 200)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExchangeStatus status;

    /** 거절·취소된 이유(화면에 보여 줌) */
    @Column(length = 120)
    private String closeReason;

    /** 이 요청과 함께 열린 채팅방 번호(연관 관계 대신 번호만 저장) */
    private Long chatRoomId;

    @Column(nullable = false)
    private Instant createdAt;

    /** 수락·거절·취소된 시각(대기 중이면 null) */
    private Instant respondedAt;

    public ExchangeRequest(AppUser requester, Item offeredItem, Item targetItem, String message) {
        this.requester = requester;
        this.targetOwner = targetItem.getOwner(); // 받는 사람은 원하는 물건의 주인으로 자동 결정
        this.offeredItem = offeredItem;
        this.targetItem = targetItem;
        this.message = message;
        this.status = ExchangeStatus.REQUESTED;   // 새 요청은 항상 '대기' 상태에서 시작
        this.createdAt = Instant.now();
    }

    public void linkChatRoom(long roomId) {
        this.chatRoomId = roomId;
    }

    public void accept() {
        close(ExchangeStatus.ACCEPTED, null);
    }

    public void reject() {
        close(ExchangeStatus.REJECTED, "상대가 교환을 거절했어요.");
    }

    public void cancel(String reason) {
        close(ExchangeStatus.CANCELED, reason);
    }

    /** 이 사용자가 보낸 사람인가 (requester.getId() 는 Long → == 비교 시 long 으로 자동 언박싱) */
    public boolean isRequestedBy(long userId) {
        return requester.getId() == userId;
    }

    /** 이 사용자가 받은 사람인가 */
    public boolean isReceivedBy(long userId) {
        return targetOwner.getId() == userId;
    }

    /** 상태 전이의 단일 관문: REQUESTED 가 아니면 409, 맞으면 다음 상태·이유·시각을 기록 */
    private void close(ExchangeStatus next, String reason) {
        if (status != ExchangeStatus.REQUESTED) {
            throw ApiException.conflict("INVALID_STATUS",
                    "이미 처리된 요청입니다(현재 상태: " + status.name() + ").");
        }
        this.status = next;
        this.closeReason = reason;
        this.respondedAt = Instant.now();
    }
}
