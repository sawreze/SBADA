package com.sbada.item;

import java.time.Instant;

import com.sbada.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 찜(관심목록) 한 건: 사용자 + 물건. 표는 Flyway V5__item_likes.sql 이 만든다.
 *
 * [프로그래밍 설명]
 *  - @UniqueConstraint: 같은 사용자가 같은 물건을 두 번 찜할 수 없게 DB 가 막는다(서비스에서도 먼저 확인).
 *  - @ManyToOne(fetch = LAZY): 찜 목록을 읽을 때 물건은 실제로 쓸 때만 불러온다.
 */
@Entity
@Table(name = "item_likes",
        uniqueConstraints = @UniqueConstraint(name = "ux_item_likes_user_item", columnNames = {"user_id", "item_id"}),
        indexes = @Index(name = "idx_item_likes_user", columnList = "user_id, id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id")
    private Item item;

    @Column(nullable = false)
    private Instant createdAt;

    public ItemLike(AppUser user, Item item) {
        this.user = user;
        this.item = item;
        this.createdAt = Instant.now();
    }
}
