package com.sbada.user;

import java.time.Instant;

import com.sbada.common.Category;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자의 카테고리 선호도 가중치(0.0 ~ 1.0).
 * 온보딩에서 고른 카테고리는 1.0에서 시작하고, 이후 행동(조회·채팅·교환요청)으로 조금씩 학습된다.
 *
 * [프로그래밍 설명]
 *  - 테이블 user_preferences: (user_id, category) 조합은 하나만 있을 수 있다(@UniqueConstraint)
 *    → 한 사용자의 한 카테고리에 가중치 1개.
 *  - @Index: category 열에 인덱스 → '이 카테고리에 관심 있는 사람 찾기'(findInterested, 새 물건 매칭 알림)가 빠르다.
 *  - @ManyToOne: 여러 선호도 행(N) → 한 사용자(1). DB 에는 외래 키 user_id 로 저장된다(@JoinColumn).
 *    fetch = LAZY: 선호도를 읽을 때 사용자까지 바로 읽지 않고, getUser() 로 실제로 쓸 때 읽는다(불필요한 조인 방지).
 *  - @Enumerated(EnumType.STRING): enum 을 이름 문자열("DIGITAL")로 저장. ORDINAL(순서 번호)로 저장하면
 *    enum 순서를 바꿨을 때 기존 데이터의 의미가 뒤바뀌는 위험이 있어 STRING 을 쓴다.
 */
@Entity
@Table(name = "user_preferences",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "category"}),
        indexes = @Index(name = "idx_pref_category", columnList = "category"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPreference {

    /** 가중치가 어디서 왔는지: 온보딩에서 직접 고름 / 행동으로 학습됨 */
    public enum Source { ONBOARDING, BEHAVIOR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(nullable = false)
    private double weight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(nullable = false)
    private Instant updatedAt;

    public UserPreference(AppUser user, Category category, double weight, Source source) {
        this.user = user;
        this.category = category;
        this.weight = clamp(weight);
        this.source = source;
        this.updatedAt = Instant.now();
    }

    /** 유계 증가 규칙: w ← w + rate·(1 − w). 1을 넘지 않고, 이미 높은 값은 조금만 오른다. */
    public void reinforce(double rate) {
        this.weight = clamp(weight + rate * (1.0 - weight));
        this.updatedAt = Instant.now();
    }

    /** 값을 정해진 값으로 다시 설정(온보딩 다시 고르기) */
    public void resetTo(double weight, Source source) {
        this.weight = clamp(weight);
        this.source = source;
        this.updatedAt = Instant.now();
    }

    /** 0.0 ~ 1.0 범위로 자르기 */
    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
