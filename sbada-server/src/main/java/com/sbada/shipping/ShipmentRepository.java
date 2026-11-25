package com.sbada.shipping;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.sbada.exchange.DealStage;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByExchangeIdAndSenderId(Long exchangeId, Long senderId);

    List<Shipment> findByExchangeId(Long exchangeId);

    /** 아직 도착하지 않은 운송장(배송 단계의 교환만) — 주기적으로 택배사에 물어본다 */
    @Query("select s from Shipment s where s.status <> com.sbada.shipping.Shipment.Status.DELIVERED "
            + "and s.exchange.dealStage = :stage")
    List<Shipment> findOpen(DealStage stage);
}
