package com.sbada.item;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 찜(관심목록) 저장소 */
public interface ItemLikeRepository extends JpaRepository<ItemLike, Long> {

    Optional<ItemLike> findByUserIdAndItemId(long userId, long itemId);

    /** 내 관심목록: 최근에 찜한 순. 내린(HIDDEN) 물건은 뺀다. 물건 · 주인을 한 번에 읽는다(N+1 방지). */
    @Query("select l from ItemLike l join fetch l.item i join fetch i.owner "
            + "where l.user.id = :userId and i.status <> :hidden order by l.id desc")
    List<ItemLike> findMine(@Param("userId") long userId, @Param("hidden") ItemStatus hidden);
}
