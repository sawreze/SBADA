package com.sbada.exchange;

import java.time.Instant;

import com.sbada.common.ApiException;
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
 *  - 한 요청에 사람 2명(@ManyToOne AppUser 두 개)과 물건 2개(물품 서버의 물건 번호 두 개)가 연결된다.
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

    // 2026-11-25 MSA 2단계: 물건은 물품 서버 DB 에 있다 → 번호만 들고, 화면에 필요한 정보는 배달부(gRPC)로 받는다
    /** 보낸 사람이 내놓는 물건 번호 */
    @Column(name = "offered_item_id", nullable = false)
    private Long offeredItemId;

    /** 보낸 사람이 원하는 물건 번호 */
    @Column(name = "target_item_id", nullable = false)
    private Long targetItemId;

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

    // ── 2026-11-25 금융 백엔드: 교환 추가 금액(V18) ──
    /** 추가 금액(원). 0 이면 없음 */
    @Column(name = "extra_amount", nullable = false)
    private long extraAmount;

    /** 누가 내는지: REQUESTER · OWNER (추가 금액이 없으면 null). DB 에는 문자열로 저장 */
    @Column(name = "extra_payer", length = 20)
    private String extraPayer;

    /** 안전결제 상태(PaymentStatus 이름). 돈은 결제 서비스가 갖고, 여기는 화면에 보여 줄 상태만 */
    @Column(name = "payment_status", nullable = false, length = 20)
    private String paymentStatus = PaymentStatus.NONE.name();

    /** 양쪽 수령 확인: 요청자 · 물건 주인이 각각 '물건 받았어요'를 누른 시각(둘 다 있어야 정산) */
    @Column(name = "requester_received_at")
    private Instant requesterReceivedAt;

    @Column(name = "owner_received_at")
    private Instant ownerReceivedAt;

    public ExchangeRequest(AppUser requester, AppUser targetOwner, long offeredItemId, long targetItemId,
                           String message) {
        this.requester = requester;
        this.targetOwner = targetOwner; // 받는 사람 = 원하는 물건의 주인(물품 서버가 알려 준 ownerId)
        this.offeredItemId = offeredItemId;
        this.targetItemId = targetItemId;
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

    // ───────────── 추가 금액 · 안전결제 상태 ─────────────

    public static final String PAYER_REQUESTER = "REQUESTER";
    public static final String PAYER_OWNER = "OWNER";

    /**
     * 추가 금액 조건 정하기(0 이면 없앰). 대기 · 성사 상태에서만, 그리고 아직 결제 전일 때만 바꿀 수 있다.
     * payer: REQUESTER(요청 보낸 사람이 냄) · OWNER(물건 주인이 냄)
     */
    public void setExtraTerms(long amount, String payer) {
        if (status != ExchangeStatus.REQUESTED && status != ExchangeStatus.ACCEPTED) {
            throw ApiException.conflict("INVALID_STATUS", "이미 끝난 교환이에요(현재 상태: " + status.name() + ").");
        }
        if (isPaymentLocked()) {
            throw ApiException.conflict("ALREADY_PAID", "이미 결제된 추가 금액은 바꿀 수 없어요.");
        }
        if (amount <= 0) {
            this.extraAmount = 0;
            this.extraPayer = null;
        } else {
            this.extraAmount = amount;
            this.extraPayer = payer;
        }
        this.paymentStatus = PaymentStatus.NONE.name(); // 환불된 뒤 조건을 새로 정하면 다시 '안 냄'
    }

    public boolean hasExtra() {
        return extraPayer != null && extraAmount > 0;
    }

    /** 추가 금액을 낼 사람 번호(없으면 null) */
    public Long payerId() {
        if (!hasExtra()) {
            return null;
        }
        return PAYER_REQUESTER.equals(extraPayer) ? requester.getId() : targetOwner.getId();
    }

    /** 추가 금액을 받을 사람 번호(없으면 null) */
    public Long payeeId() {
        if (!hasExtra()) {
            return null;
        }
        return PAYER_REQUESTER.equals(extraPayer) ? targetOwner.getId() : requester.getId();
    }

    public PaymentStatus payment() {
        return PaymentStatus.valueOf(paymentStatus);
    }

    /** 결제가 진행 중이거나 끝나서 조건을 바꿀 수 없는 상태 */
    public boolean isPaymentLocked() {
        PaymentStatus p = payment();
        return p == PaymentStatus.PAID_HELD || p == PaymentStatus.RELEASED || p == PaymentStatus.REFUND_PENDING;
    }

    /** 결제 상태 바꾸기. 실제로 바뀌었으면 true(같은 이벤트가 두 번 와도 한 번만 반영) */
    public boolean changePayment(PaymentStatus next) {
        if (payment() == next) {
            return false;
        }
        this.paymentStatus = next.name();
        return true;
    }

    /**
     * 물건 받음 확인(양쪽 수령 확인). 이번에 새로 확인했으면 true, 이미 확인했으면 false.
     * 두 사람이 모두 확인해야 안전결제가 받는 사람에게 정산된다.
     */
    public boolean confirmReceived(long userId) {
        if (isRequestedBy(userId)) {
            if (requesterReceivedAt != null) {
                return false;
            }
            requesterReceivedAt = Instant.now();
            return true;
        }
        if (ownerReceivedAt != null) {
            return false;
        }
        ownerReceivedAt = Instant.now();
        return true;
    }

    public boolean hasReceived(long userId) {
        return isRequestedBy(userId) ? requesterReceivedAt != null : ownerReceivedAt != null;
    }

    public boolean bothReceived() {
        return requesterReceivedAt != null && ownerReceivedAt != null;
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
