package com.sbada.payment.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletAccountRepository extends JpaRepository<WalletAccount, Long> {

    List<WalletAccount> findByUserIdOrderByIdAsc(long userId);

    Optional<WalletAccount> findByIdAndUserId(long id, long userId);

    boolean existsByUserIdAndAccountHash(long userId, String accountHash);

    long countByUserId(long userId);
}
