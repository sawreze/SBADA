package com.sbada.chat;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * chat_messages 테이블 접근.
 *
 * [프로그래밍 설명]
 *  - 메시지 id 는 계속 커지는 번호라서 '시간 순서'로도 쓴다(id > afterId = 그 뒤에 온 메시지).
 *  - countUnread 의 left join: SYSTEM 메시지는 sender 가 null 이라, 일반 join 이면 빠져 버린다.
 *    left join 으로 sender 가 없는 행도 남기고 (s is null or 내가 보낸 게 아님) 으로 '상대·시스템 메시지'만 센다.
 *  - max(m.id) 는 메시지가 하나도 없으면 null → 반환 타입을 long 이 아니라 Long 으로 받는다.
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** afterId 보다 새 메시지(재접속 후 놓친 메시지 채우기) */
    @Query("select m from ChatMessage m where m.room.id = :roomId and m.id > :afterId order by m.id asc")
    List<ChatMessage> findAfter(@Param("roomId") long roomId, @Param("afterId") long afterId, Pageable pageable);

    /** 최근 메시지(내림차순으로 읽어 화면에서 뒤집는다) */
    @Query("select m from ChatMessage m where m.room.id = :roomId order by m.id desc")
    List<ChatMessage> findLatest(@Param("roomId") long roomId, Pageable pageable);

    /** 내가 마지막으로 읽은 번호(lastReadId) 뒤에 온, 내가 보내지 않은 메시지 수 */
    @Query("""
            select count(m) from ChatMessage m left join m.sender s
            where m.room.id = :roomId and m.id > :lastReadId
              and (s is null or s.id <> :userId)
            """)
    long countUnread(@Param("roomId") long roomId, @Param("userId") long userId, @Param("lastReadId") long lastReadId);

    /** 방의 마지막 메시지 번호(읽음 처리용) */
    @Query("select max(m.id) from ChatMessage m where m.room.id = :roomId")
    Long maxId(@Param("roomId") long roomId);
}
