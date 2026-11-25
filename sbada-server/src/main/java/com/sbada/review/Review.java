package com.sbada.review;

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

/** 거래 후기 (2026-11-25 사용자 요청, V21 reviews). 교환 · 쓴 사람마다 하나, 받은 사람(target) 프로필에 보인다 */
@Entity
@Table(name = "reviews")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_id", nullable = false)
    private Long exchangeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "writer_id")
    private AppUser writer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id")
    private AppUser target;

    /** 별점 1~5 */
    @Column(nullable = false)
    private int rating;

    @Column(length = 300)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Review(long exchangeId, AppUser writer, AppUser target, int rating, String content) {
        this.exchangeId = exchangeId;
        this.writer = writer;
        this.target = target;
        this.rating = rating;
        this.content = content;
        this.createdAt = Instant.now();
    }
}
