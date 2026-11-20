package com.sbada.payment.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 지갑 원장 한 줄. 한 번 쓰면 바꾸지 않는다(잔액 = 마지막 줄의 balanceAfter) */
@Entity
@Table(name = "wallet_transactions")
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tx_type", nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false)
    private Long fee;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(length = 20)
    private String method;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "counterpart_id")
    private Long counterpartId;

    @Column(length = 100)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletTransaction() {
    }

    public WalletTransaction(long userId, TxType type, long amount, long fee, long balanceAfter, String method,
                             Long accountId, Long counterpartId, String memo) {
        this.userId = userId;
        this.type = type.name();
        this.amount = amount;
        this.fee = fee;
        this.balanceAfter = balanceAfter;
        this.method = method;
        this.accountId = accountId;
        this.counterpartId = counterpartId;
        this.memo = memo;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getType() { return type; }
    public Long getAmount() { return amount; }
    public Long getFee() { return fee; }
    public Long getBalanceAfter() { return balanceAfter; }
    public String getMethod() { return method; }
    public Long getAccountId() { return accountId; }
    public Long getCounterpartId() { return counterpartId; }
    public String getMemo() { return memo; }
    public Instant getCreatedAt() { return createdAt; }
}
