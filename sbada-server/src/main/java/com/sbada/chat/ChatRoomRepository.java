package com.sbada.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** chat_rooms 테이블 접근 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    /** 이 물건에 이 사람이 건 방(있으면 재사용, 없으면 새로 만든다) */
    @Query("select r from ChatRoom r where r.item.id = :itemId and r.partner.id = :partnerId")
    Optional<ChatRoom> findByItemAndPartner(@Param("itemId") long itemId, @Param("partnerId") long partnerId);

    /** 내가 주인이거나 상대인 방, 최근 대화순 — GET /api/chats */
    @Query("""
            select r from ChatRoom r
            where r.owner.id = :userId or r.partner.id = :userId
            order by r.lastMessageAt desc
            """)
    List<ChatRoom> findMine(@Param("userId") long userId);
}
