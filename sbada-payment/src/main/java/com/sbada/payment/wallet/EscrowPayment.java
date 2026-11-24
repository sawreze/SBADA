package com.sbada.payment.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 안전결제(에스크로) 한 건 — 교환 하나에 걸린 추가 금액.
 *  HELD(보관 중) → RELEASED(받는 사람 지갑으로 정산) 또는 REFUNDED(낸 사람에게 환불). 끝난 건은 다시 바뀌지 않는다.
 */
@Entity
@Table(name = "escrow_payments")
public class EscrowPayment {

    public static final String HELD = "HELD";
    public static final String RELEASED = "RELEASED";
    public static final String REFUNDED = "REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_id", nullable = false)
    private Long exchangeId;

    @Column(name = "payer_id", nullable = false)
    private Long payerId;

    @Column(name = "payee_id", nullable = false)
    private Long payeeId;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private Long fee;

    @Column(nullable = false, length = 20)
    private String method;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "release_after", nullable = false)
    private Instant releaseAfter;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EscrowPayment() {
    }

    public EscrowPayment(long exchangeId, long payerId, long payeeId, long amount, long fee, String method,
                         Instant releaseAfter) {
        this.exchangeId = exchangeId;
        this.payerId = payerId;
        this.payeeId = payeeId;
        this.amount = amount;
        this.fee = fee;
        this.method = method;
        this.status = HELD;
        this.releaseAfter = releaseAfter;
        this.createdAt = Instant.now();
    }

    public boolean isHeld() {
        return HELD.equals(status);
    }

    void release() {
        this.status = RELEASED;
        this.settledAt = Instant.now();
    }

    void refund() {
        this.status = REFUNDED;
        this.settledAt = Instant.now();
    }

    /** 낸 사람이 실제로 낸 돈 = 금액 + 수수료 */
    public long total() {
        return amount + fee;
    }

    public Long getId() { return id; }
    public Long getExchangeId() { return exchangeId; }
    public Long getPayerId() { return payerId; }
    public Long getPayeeId() { return payeeId; }
    public Long getAmount() { return amount; }
    public Long getFee() { return fee; }
    public String getMethod() { return method; }
    public String getStatus() { return status; }
    public Instant getReleaseAfter() { return releaseAfter; }
    public Instant getSettledAt() { return settledAt; }
    public Instant getCreatedAt() { return createdAt; }
}
