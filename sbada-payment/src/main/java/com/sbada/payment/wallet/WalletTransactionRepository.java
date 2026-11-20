package com.sbada.payment.wallet;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findFirstByUserIdOrderByIdDesc(long userId);

    List<WalletTransaction> findTop30ByUserIdOrderByIdDesc(long userId);

    boolean existsByUserId(long userId);
}
