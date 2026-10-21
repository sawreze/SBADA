package com.sbada.notification;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * notifications 테이블 접근.
 *
 * [프로그래밍 설명]
 *  - @Modifying + update JPQL: 엔티티를 하나씩 읽어 바꾸지 않고 UPDATE 문 한 번으로 여러 행을 바꾼다(벌크 연산).
 *    반환값 int = 바뀐 행 수. 반드시 트랜잭션 안에서 불러야 한다(NotificationService.markAllRead 에 @Transactional).
 *  - n.read 필드는 DB 열 이름이 is_read 다(read 는 SQL 에서 헷갈려 엔티티에서 @Column(name="is_read") 로 바꿈).
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 내 알림 최신순(개수는 Pageable 로 제한) */
    @Query("select n from Notification n where n.user.id = :userId order by n.id desc")
    List<Notification> findRecent(@Param("userId") long userId, Pageable pageable);

    @Query("select count(n) from Notification n where n.user.id = :userId and n.read = false")
    long countUnread(@Param("userId") long userId);

    @Modifying
    @Query("update Notification n set n.read = true where n.user.id = :userId and n.read = false")
    int markAllRead(@Param("userId") long userId);
}
