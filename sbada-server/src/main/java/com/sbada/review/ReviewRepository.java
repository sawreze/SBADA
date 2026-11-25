package com.sbada.review;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByExchangeIdAndWriterId(Long exchangeId, Long writerId);

    @Query("select r from Review r join fetch r.writer where r.target.id = :targetId order by r.id desc")
    List<Review> findForTarget(long targetId, Pageable page);

    long countByTargetId(Long targetId);

    @Query("select coalesce(avg(r.rating), 0) from Review r where r.target.id = :targetId")
    double averageFor(long targetId);
}
