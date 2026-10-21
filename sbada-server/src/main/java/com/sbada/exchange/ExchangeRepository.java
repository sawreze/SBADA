package com.sbada.exchange;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * exchange_requests 테이블 접근.
 *
 * [프로그래밍 설명]
 *  - select count(e) > 0 … : JPQL 에서 '있는가?'를 boolean 으로 바로 돌려받는 방법.
 *  - findInvolving: 주어진 물건들이 '내놓은 쪽' 또는 '원하는 쪽'으로 걸린 요청을 모두 찾는다.
 *    교환이 성사되거나 물건을 내릴 때, 그 물건이 걸린 나머지 대기 요청을 한꺼번에 취소하는 데 쓴다.
 *  - 엔티티의 @Index(target_item_id, status 등)가 이 조회들을 빠르게 한다.
 */
public interface ExchangeRepository extends JpaRepository<ExchangeRequest, Long> {

    /** 이 두 물건(내놓은 → 원하는) 조합으로 이 상태의 요청이 있는가 */
    @Query("""
            select count(e) > 0 from ExchangeRequest e
            where e.offeredItem.id = :offeredId and e.targetItem.id = :targetId and e.status = :status
            """)
    boolean existsBetween(@Param("offeredId") long offeredId, @Param("targetId") long targetId,
                          @Param("status") ExchangeStatus status);

    /** 이 물건들이 어느 쪽으로든 걸려 있는, 이 상태의 요청들 */
    @Query("""
            select e from ExchangeRequest e
            where e.status = :status and (e.offeredItem.id in :itemIds or e.targetItem.id in :itemIds)
            """)
    List<ExchangeRequest> findInvolving(@Param("itemIds") Collection<Long> itemIds,
                                        @Param("status") ExchangeStatus status);

    /** 내가 받은 요청(최신순) — GET /api/exchanges?box=received */
    @Query("select e from ExchangeRequest e where e.targetOwner.id = :userId order by e.id desc")
    List<ExchangeRequest> findReceived(@Param("userId") long userId);

    /** 내가 보낸 요청(최신순) — GET /api/exchanges?box=sent */
    @Query("select e from ExchangeRequest e where e.requester.id = :userId order by e.id desc")
    List<ExchangeRequest> findSent(@Param("userId") long userId);

    /** 내가 보낸 요청 중 특정 상태(추천 화면에 '요청 보냄' 표시용) */
    @Query("select e from ExchangeRequest e where e.requester.id = :userId and e.status = :status")
    List<ExchangeRequest> findSentWithStatus(@Param("userId") long userId, @Param("status") ExchangeStatus status);

    /** 받은 요청 중 특정 상태의 개수(대기 중인 받은 요청 수 배지) */
    @Query("select count(e) from ExchangeRequest e where e.targetOwner.id = :userId and e.status = :status")
    long countReceivedWithStatus(@Param("userId") long userId, @Param("status") ExchangeStatus status);
}
