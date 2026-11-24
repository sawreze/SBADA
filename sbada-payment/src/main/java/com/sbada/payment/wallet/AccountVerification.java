package com.sbada.payment.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 1원 계좌 인증 한 건. 계좌번호는 처음부터 암호화된 값만 들고 있고, 인증 숫자는 HMAC 값만 저장한다.
 * 5분 안에 5번까지 맞혀 볼 수 있다.
 */
@Entity
@Table(name = "account_verifications")
public class AccountVerification {

    @Id
    @Column(length = 36)
    private String id;

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

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountVerification() {
    }

    public AccountVerification(String id, long userId, String bankCode, String accountMasked, String holder,
                               String accountHash, String encAccount, String encKey, String codeHash,
                               Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.bankCode = bankCode;
        this.accountMasked = accountMasked;
        this.holder = holder;
        this.accountHash = accountHash;
        this.encAccount = encAccount;
        this.encKey = encKey;
        this.codeHash = codeHash;
        this.attempts = 0;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    boolean isVerified() {
        return verifiedAt != null;
    }

    void failAttempt() {
        this.attempts++;
    }

    void markVerified() {
        this.verifiedAt = Instant.now();
    }

    public String getId() { return id; }
    public Long getUserId() { return userId; }
    public String getBankCode() { return bankCode; }
    public String getAccountMasked() { return accountMasked; }
    public String getHolder() { return holder; }
    public String getAccountHash() { return accountHash; }
    public String getEncAccount() { return encAccount; }
    public String getEncKey() { return encKey; }
    public String getCodeHash() { return codeHash; }
    public int getAttempts() { return attempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
