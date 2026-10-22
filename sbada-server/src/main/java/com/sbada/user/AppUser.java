package com.sbada.user;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. 비밀번호는 원문이 아니라 BCrypt 해시만 저장한다(7장 6.2).
 *
 * [프로그래밍 설명 — JPA 엔티티]
 *  - @Entity: 이 클래스의 객체 1개 = DB 테이블의 행(row) 1개. JPA(Hibernate)가 SQL 을 대신 만들어 준다.
 *  - @Table(name = "app_users"): 연결할 테이블 이름. ('user' 는 SQL 예약어라 피했다)
 *    테이블 자체는 Flyway(db/migration/V1__init_schema.sql)가 만들고, JPA 는 시작할 때
 *    엔티티와 테이블 구조가 맞는지만 확인한다(spring.jpa.hibernate.ddl-auto=validate).
 *  - @Id + @GeneratedValue(IDENTITY): 기본 키. INSERT 할 때 DB 가 1, 2, 3… 번호를 자동으로 매긴다.
 *  - @Column(nullable=false, unique=true, length=20): NOT NULL·UNIQUE·VARCHAR(20) 제약과 맞춘 설정.
 *    필드 이름 priceMin 은 자동으로 열 이름 price_min 으로 바뀐다(스네이크 케이스 변환).
 *  - Lombok: 컴파일할 때 코드를 대신 만들어 주는 라이브러리.
 *      @Getter → getId(), getUsername() … (boolean 은 isOnboarded())
 *      @NoArgsConstructor(access = PROTECTED) → 매개변수 없는 protected 생성자.
 *        JPA 는 DB 에서 읽은 행으로 객체를 만들 때 빈 생성자가 꼭 필요하다. protected 라서 우리 코드는 못 쓴다.
 *  - setter 를 두지 않고 completeOnboarding() 처럼 '의미 있는 메서드'로만 상태를 바꾼다(아무 데서나 값을 못 바꾸게).
 *  - 변경 감지(dirty checking): @Transactional 안에서 엔티티 필드를 바꾸면 save() 를 부르지 않아도
 *    트랜잭션이 끝날 때 JPA 가 바뀐 부분을 UPDATE 한다.
 */
@Entity
@Table(name = "app_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 12)
    private String nickname;

    @Column(nullable = false, length = 20)
    private String region;

    /** 알림 설정(V14, 계정정보 > 알림 설정): 찜 · 채팅 · 교환 알림을 받을지. 기본 모두 true */
    @Column(name = "notify_like", nullable = false)
    private boolean notifyLike = true;

    @Column(name = "notify_chat", nullable = false)
    private boolean notifyChat = true;

    @Column(name = "notify_exchange", nullable = false)
    private boolean notifyExchange = true;

    /** 하나 더 추가한 동네(Flyway V13, 없으면 null). 내 동네는 최대 2개 — 홈 왼쪽 위에서 region 과 서로 바꾼다. */
    @Column(name = "second_region", length = 20)
    private String secondRegion;

    /** 프로필 사진(item_images.id, Flyway V6). 사진은 POST /api/images 로 올린 것을 그대로 가리킨다. */
    @Column(name = "profile_image_id")
    private Long profileImageId;

    /** 첫 실행 선호도 선택(온보딩)을 마쳤는지 */
    @Column(nullable = false)
    private boolean onboarded;

    /** 온보딩에서 고른 '주로 교환할 가격대' (Integer: null 가능 = '상관없음') */
    private Integer priceMin;
    private Integer priceMax;

    /**
     * 온보딩에서 고른 관심 키워드(예: 닌텐도, 캠핑)
     * @ElementCollection: 엔티티가 아닌 값(문자열) 목록을 별도 테이블(user_keywords)에 저장한다.
     *   user_keywords(user_id, keyword) 행 여러 개 ↔ 이 Set 하나.
     * FetchType.EAGER: 사용자를 읽을 때 키워드도 바로 함께 읽는다(개수가 적어서 부담 없음).
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_keywords", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "keyword", length = 20)
    private Set<String> keywords = new LinkedHashSet<>();

    /** 카카오 로그인으로 만든 계정이면 카카오 회원번호(앱마다 고유한 숫자). 이메일 가입 계정은 null. V3 Flyway 로 추가 */
    @Column(name = "kakao_id", unique = true)
    private Long kakaoId;

    @Column(nullable = false)
    private Instant createdAt; // Instant: 시간대와 무관한 UTC 시각(DB: timestamp with time zone)

    /** 회원가입 때 쓰는 생성자(AuthService.signup). passwordHash 는 이미 BCrypt 로 바꾼 값이어야 한다. */
    public AppUser(String username, String passwordHash, String nickname, String region) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.region = region;
        this.createdAt = Instant.now();
    }

    /**
     * 온보딩 저장(PUT /api/me/preferences → PreferenceService.saveOnboarding).
     * 트랜잭션 안에서 불리므로, 끝나면 JPA 가 app_users·user_keywords 를 자동으로 UPDATE/INSERT/DELETE 한다.
     */
    /**
     * 카카오 로그인으로 처음 들어온 사람의 계정을 만든다(정적 팩토리 메서드).
     * 비밀번호 로그인은 쓰지 않으므로 passwordHash 에는 아무도 모르는 임의 값의 해시를 넣는다(열이 NOT NULL 이라서).
     */
    public static AppUser forKakao(long kakaoId, String username, String unusablePasswordHash, String nickname,
                                   String region) {
        AppUser user = new AppUser(username, unusablePasswordHash, nickname, region);
        user.kakaoId = kakaoId;
        return user;
    }

    public void completeOnboarding(Set<String> keywords, Integer priceMin, Integer priceMax) {
        this.keywords.clear();
        this.keywords.addAll(keywords);
        this.priceMin = priceMin;
        this.priceMax = priceMax;
        this.onboarded = true;
    }

    /** 계정정보 > 내 동네 설정(PUT /api/me/region). 앞뒤 공백은 지운다. */
    public void changeRegion(String region) {
        this.region = region.trim();
        // 두 번째 동네와 같아지면 같은 동네가 두 번 남지 않게 두 번째를 비운다
        if (this.region.equals(this.secondRegion)) {
            this.secondRegion = null;
        }
    }

    /** 계정정보 > 동네 추가(PUT /api/me/second-region). null 이면 지운다. */
    public void changeSecondRegion(String secondRegion) {
        this.secondRegion = secondRegion == null ? null : secondRegion.trim();
    }

    /** 알림 설정 바꾸기(PUT /api/me/notification-settings). null 인 값은 그대로 둔다(보낸 것만 바뀜) */
    public void changeNotificationSettings(Boolean like, Boolean chat, Boolean exchange) {
        if (like != null) {
            this.notifyLike = like;
        }
        if (chat != null) {
            this.notifyChat = chat;
        }
        if (exchange != null) {
            this.notifyExchange = exchange;
        }
    }

    /** 이 종류(like · chat · exchange)의 알림을 받는지. 설정에 없는 종류(null)는 항상 받는다 */
    public boolean wantsNotification(String settingKey) {
        if (settingKey == null) {
            return true;
        }
        return switch (settingKey) {
            case "like" -> notifyLike;
            case "chat" -> notifyChat;
            case "exchange" -> notifyExchange;
            default -> true;
        };
    }

    /** 홈 왼쪽 위 동네 바꾸기(POST /api/me/region/swap): 지금 동네 ↔ 추가한 동네 */
    public void swapRegions() {
        String current = this.region;
        this.region = this.secondRegion;
        this.secondRegion = current;
    }

    /** 계정정보 > 내 정보(PUT /api/me/profile). 아이디가 바뀌어도 로그인 토큰은 사용자 번호(sub) 기준이라 그대로 쓸 수 있다. */
    public void changeProfile(String username, String nickname) {
        this.username = username;
        this.nickname = nickname.trim();
    }

    /** 계정정보 > 내 정보 > 비밀번호 변경(PUT /api/me/password). 해시만 저장한다. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** 프로필 사진(PUT /api/me/photo). null 이면 사진 없음(닉네임 첫 글자) */
    public void changeProfileImage(Long imageId) {
        this.profileImageId = imageId;
    }
}
