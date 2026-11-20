package com.sbada.payment.events;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop20BySentAtIsNullOrderByIdAsc();

    List<OutboxEvent> findByUserIdOrderByIdAsc(long userId);
}
