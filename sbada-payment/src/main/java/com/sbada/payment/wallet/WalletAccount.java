package com.sbada.payment.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 출금 계좌. 계좌번호 원문은 없다(enc_account · enc_key 로만 보관) */
@Entity
@Table(name = "wallet_accounts")
public class WalletAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "account_masked", nullable = false, length = 30)
    private String accountMasked;

    @Column(nullable = false, length = 30)
    private String holder;

    @Column(name = "account_hash", nullable = false, length = 64)
    private String accountHash;

    @Column(name = "enc_account", nullable = false, length = 200)
    private String encAccount;

    @Column(name = "enc_key", nullable = false, length = 700)
    private String encKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletAccount() {
    }

    public WalletAccount(long userId, String bankCode, String accountMasked, String holder, String accountHash,
                         String encAccount, String encKey) {
        this.userId = userId;
        this.bankCode = bankCode;
        this.accountMasked = accountMasked;
        this.holder = holder;
        this.accountHash = accountHash;
        this.encAccount = encAccount;
        this.encKey = encKey;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getBankCode() { return bankCode; }
    public String getAccountMasked() { return accountMasked; }
    public String getHolder() { return holder; }
    public String getAccountHash() { return accountHash; }
    public String getEncAccount() { return encAccount; }
    public String getEncKey() { return encKey; }
    public Instant getCreatedAt() { return createdAt; }
}
