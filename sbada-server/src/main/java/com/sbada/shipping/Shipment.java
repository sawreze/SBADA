package com.sbada.shipping;

import java.time.Instant;

import com.sbada.exchange.ExchangeRequest;
import com.sbada.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 운송장 하나 (2026-11-25 사용자 요청, V21 shipments). 교환 · 보낸 사람마다 하나 — 다시 입력하면 고쳐 쓴다.
 *  status: VERIFIED(택배사 접수 확인) → IN_TRANSIT(이동 중) → DELIVERED(배송 완료)
 *  level : 스마트택배 배송 단계 1 준비 · 2 집화 · 3 배송 중 · 4 지점 도착 · 5 배송 출발 · 6 배송 완료
 */
@Entity
@Table(name = "shipments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment {

    public enum Status { VERIFIED, IN_TRANSIT, DELIVERED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_id")
    private ExchangeRequest exchange;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id")
    private AppUser sender;

    @Column(name = "carrier_code", nullable = false, length = 10)
    private String carrierCode;

    @Column(name = "carrier_name", nullable = false, length = 30)
    private String carrierName;

    @Column(name = "invoice_no", nullable = false, length = 30)
    private String invoiceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private int level;

    @Column(name = "last_detail", length = 120)
    private String lastDetail;

    @Column(name = "last_where", length = 60)
    private String lastWhere;

    @Column(name = "last_time", length = 30)
    private String lastTime;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** 마지막으로 택배사에 물어본 시각(조회 간격 조절) */
    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    public Shipment(ExchangeRequest exchange, AppUser sender) {
        this.exchange = exchange;
        this.sender = sender;
    }

    /** 새 운송장으로 (다시) 등록: 상태를 처음부터 */
    public void register(String carrierCode, String carrierName, String invoiceNo, TrackResult first) {
        this.carrierCode = carrierCode;
        this.carrierName = carrierName;
        this.invoiceNo = invoiceNo;
        this.verifiedAt = Instant.now();
        this.deliveredAt = null;
        this.status = Status.VERIFIED;
        this.level = 0;
        apply(first);
    }

    /** 조회 결과 반영. 단계가 바뀌었으면 true */
    public boolean apply(TrackResult r) {
        this.checkedAt = Instant.now();
        if (r == null || !r.found()) {
            return false;
        }
        boolean changed = r.level() != level || (r.detail() != null && !r.detail().equals(lastDetail));
        this.level = r.level();
        this.lastDetail = cut(r.detail(), 120);
        this.lastWhere = cut(r.where(), 60);
        this.lastTime = cut(r.time(), 30);
        if (r.delivered()) {
            if (status != Status.DELIVERED) {
                this.status = Status.DELIVERED;
                this.deliveredAt = Instant.now();
                changed = true;
            }
        } else if (r.level() >= 3) {
            this.status = Status.IN_TRANSIT;
        }
        return changed;
    }

    /** 시연 도구(dev 전용): 등록 시각을 앞당겨 테스트 택배 단계가 바로 끝까지 가게 한다 */
    public void rewindForDemo(java.time.Duration d) {
        this.verifiedAt = verifiedAt.minus(d);
        this.checkedAt = checkedAt.minus(d);
    }

    public boolean isDelivered() {
        return status == Status.DELIVERED;
    }

    /** 화면용: 운송장 번호 가운데를 가린다(1234****5678) */
    public String maskedInvoice() {
        String n = invoiceNo == null ? "" : invoiceNo;
        return n.length() <= 8 ? n : n.substring(0, 4) + "*".repeat(n.length() - 8) + n.substring(n.length() - 4);
    }

    private static String cut(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
