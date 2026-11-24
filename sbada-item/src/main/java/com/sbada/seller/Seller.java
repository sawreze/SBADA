package com.sbada.seller;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 판매자(물건 주인) 사본 — 물품 서버의 '읽기 모델' (2026-11-25 MSA 2단계)
 *
 * 회원의 원본은 메인 서버(회원 서비스)에 있다. 물품 서버는 목록 · 검색에 꼭 필요한 칸(닉네임 · 동네)만 복사해 둔다.
 *  - 동네로 거르는 검색(JPQL 의 i.owner.region)을 다른 서버에 묻지 않고 이 DB 안에서 바로 할 수 있다.
 *  - 회원이 닉네임 · 동네를 바꾸면 메인 서버가 허브(Kafka sbada.user.events)로 알려 주고, SellerDirectory 가 고친다.
 *  - 처음 보는 회원이면 배달부(gRPC UserService.GetUsers)로 물어보고 저장한다.
 * id 는 메인 서버의 회원 번호를 그대로 쓴다(자동 증가 아님).
 */
@Entity
@Table(name = "sellers")
public class Seller {

    @Id
    private Long id;

    @Column(nullable = false, length = 12)
    private String nickname;

    @Column(nullable = false, length = 20)
    private String region;

    @Column(name = "second_region", length = 20)
    private String secondRegion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Seller() {
    }

    public Seller(long id, String nickname, String region, String secondRegion) {
        this.id = id;
        apply(nickname, region, secondRegion);
    }

    /** 메인 서버에서 온 최신 값으로 바꾼다 */
    public void apply(String nickname, String region, String secondRegion) {
        this.nickname = nickname == null || nickname.isBlank() ? "회원" + id : nickname;
        this.region = region == null ? "" : region;
        this.secondRegion = secondRegion == null || secondRegion.isBlank() ? null : secondRegion;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getNickname() { return nickname; }
    public String getRegion() { return region; }
    public String getSecondRegion() { return secondRegion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
