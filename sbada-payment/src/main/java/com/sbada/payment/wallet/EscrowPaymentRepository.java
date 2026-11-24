package com.sbada.payment.wallet;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EscrowPaymentRepository extends JpaRepository<EscrowPayment, Long> {

    /** 교환 하나의 가장 최근 안전결제(환불 뒤 다시 낸 경우 마지막 것) */
    Optional<EscrowPayment> findFirstByExchangeIdOrderByIdDesc(long exchangeId);

    boolean existsByExchangeIdAndStatusIn(long exchangeId, Collection<String> statuses);

    /** 자동 정산 · 대사(reconcile) 대상: 보관 중인 것 오래된 순 */
    List<EscrowPayment> findTop50ByStatusOrderByIdAsc(String status);
}
