package com.sbada.item;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sbada.common.Category;
import com.sbada.common.ItemCondition;

/**
 * items 테이블 접근.
 *
 * [프로그래밍 설명]
 *  - @Query 안의 """ … """ 는 Java 텍스트 블록(여러 줄 문자열)이다.
 *  - 쿼리는 JPQL: 테이블·열 이름이 아니라 엔티티(Item)·필드(i.owner.id, i.itemCondition)를 쓴다.
 *  - '선택 조건' 패턴: (:category is null or i.category = :category)
 *      파라미터가 null 이면 조건이 항상 참 → 거르지 않음. 값이 있으면 그 값으로 거른다. 쿼리 하나로 여러 검색을 처리.
 *  - Pageable(PageRequest.of(페이지, 개수)): 몇 개까지 가져올지 → SQL 의 LIMIT/OFFSET 으로 바뀐다.
 *  - :statuses 처럼 Collection 을 넘기면 IN (?, ?, …) 으로 펼쳐진다.
 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    /** 공개 목록 검색: 상태 + (선택) 카테고리 + (선택) 이름 부분 일치(대소문자 무시), 최신순 */
    @Query("""
            select i from Item i
            where i.status = :status
              and (:category is null or i.category = :category)
              and (:q is null or lower(i.title) like lower(concat('%', :q, '%')))
            order by i.id desc
            """)
    List<Item> search(@Param("status") ItemStatus status, @Param("category") Category category,
                      @Param("q") String q, Pageable pageable);

    /** 내 물건(숨긴 것 제외). <> 는 '같지 않다' */
    @Query("select i from Item i where i.owner.id = :ownerId and i.status <> :hidden order by i.id desc")
    List<Item> findMine(@Param("ownerId") long ownerId, @Param("hidden") ItemStatus hidden);

    /** 추천 1단계(후보 생성): 가격 밴드 안의, 남의, 교환 가능한 물건만 DB에서 먼저 거른다. */
    @Query("""
            select i from Item i
            where i.status = :status and i.owner.id <> :userId
              and i.price between :lo and :hi
            order by i.id desc
            """)
    List<Item> findPriceBand(@Param("status") ItemStatus status, @Param("userId") long userId,
                             @Param("lo") int lo, @Param("hi") int hi, Pageable pageable);

    /** 남의 교환 가능한 물건(홈 추천 피드의 후보) */
    @Query("select i from Item i where i.status = :status and i.owner.id <> :userId order by i.id desc")
    List<Item> findOthers(@Param("status") ItemStatus status, @Param("userId") long userId, Pageable pageable);

    /** AI 가격 보정용: 같은 카테고리·상태의 앱 내 시세 표본 (엔티티가 아니라 가격 숫자만 SELECT) */
    @Query("""
            select i.price from Item i
            where i.category = :category and i.itemCondition = :condition and i.status in :statuses
            order by i.id desc
            """)
    List<Integer> marketPrices(@Param("category") Category category, @Param("condition") ItemCondition condition,
                               @Param("statuses") Collection<ItemStatus> statuses, Pageable pageable);

    /** 여러 사용자의 물건을 한 번에(새 물건 매칭 알림에서 '그 사람들이 가진 물건' 찾기) */
    @Query("select i from Item i where i.owner.id in :ownerIds and i.status = :status")
    List<Item> findByOwnersAndStatus(@Param("ownerIds") Collection<Long> ownerIds, @Param("status") ItemStatus status);
}
