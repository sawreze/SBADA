package com.sbada.payment.wallet;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountVerificationRepository extends JpaRepository<AccountVerification, String> {

    /** 짧은 시간에 인증을 너무 많이 시작하지 못하게(무작위 대입 · 남의 계좌 확인 시도 방지) */
    long countByUserIdAndCreatedAtAfter(long userId, Instant since);
}
