package com.sbada.notification;

import java.time.Instant;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * FCM 기기 토큰. 로그인 JWT와는 다른 값이다(10장 7.3):
 * JWT = "누가 요청했는가", FCM 토큰 = "어느 기기로 알림을 보낼까".
 *
 * [프로그래밍 설명]
 *  - 한 사용자가 여러 기기(폰·태블릿)를 쓸 수 있으므로 사용자 1 : 토큰 N (@ManyToOne).
 *  - token 은 unique → 같은 폰의 토큰은 한 행뿐. 다른 계정으로 로그인하면 reassign 으로 주인만 바꾼다.
 */
@Entity
@Table(name = "device_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false, unique = true, length = 300)
    private String token;

    @Column(length = 20)
    private String platform;

    @Column(nullable = false)
    private Instant updatedAt;

    public DeviceToken(AppUser user, String token, String platform) {
        this.user = user;
        this.token = token;
        this.platform = platform;
        this.updatedAt = Instant.now();
    }

    /** 같은 기기에 다른 계정으로 로그인했을 때 토큰의 주인을 바꾼다 */
    public void reassign(AppUser user, String platform) {
        this.user = user;
        this.platform = platform;
        this.updatedAt = Instant.now();
    }
}
