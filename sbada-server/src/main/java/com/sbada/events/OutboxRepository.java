package com.sbada.events;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100BySentAtIsNullOrderByIdAsc();

    List<OutboxEvent> findByTopicOrderByIdAsc(String topic);
}
