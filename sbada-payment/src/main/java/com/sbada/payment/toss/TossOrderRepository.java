package com.sbada.payment.toss;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TossOrderRepository extends JpaRepository<TossOrder, Long> {

    Optional<TossOrder> findByOrderId(String orderId);

    List<TossOrder> findTop50ByStatusOrderByIdAsc(String status);

    Optional<TossOrder> findFirstByExchangeIdAndStatusOrderByIdDesc(Long exchangeId, String status);

    Optional<TossOrder> findFirstByExchangeIdOrderByIdDesc(Long exchangeId);
}
