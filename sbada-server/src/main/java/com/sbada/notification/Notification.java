package com.sbada.notification;

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
 * 알림함에 남는 알림. 실시간 전송(WebSocket)·푸시(FCM)와 별개로 DB에 기록해 다시 볼 수 있게 한다.
 *
 * [프로그래밍 설명]
 *  - refType + refId: 알림이 가리키는 대상을 '종류 + 번호'로 저장(여러 테이블을 가리킬 수 있게 외래 키 대신 사용).
 *  - boolean read 필드는 Lombok 이 isRead() 게터를 만든다. 열 이름은 is_read(예약어 충돌 방지).
 *  - @Index(user_id, id): '내 알림 최신순' 조회를 빠르게.
 */
@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_noti_user", columnList = "user_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 알림을 받는 사람 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "noti_type", nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(length = 300)
    private String body;

    /** 눌렀을 때 열 대상: ITEM · EXCHANGE · CHAT */
    @Column(length = 20)
    private String refType;

    private Long refId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(nullable = false)
    private Instant createdAt;

    public Notification(AppUser user, NotificationType type, String title, String body, String refType, Long refId) {
        this.user = user;
        this.type = type;
        this.title = title;
        this.body = body;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = Instant.now();
    }

    public void markRead() {
        this.read = true;
    }
}
