package com.sbada.notification;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * device_tokens 테이블 접근.
 *  - tokensOf: 엔티티 전체가 아니라 토큰 문자열만 SELECT(필요한 것만 읽기).
 *  - deleteByToken: 이름 규칙 삭제 메서드. FCM 이 '없는 기기'라고 답하면 FcmPushSender 가 부른다.
 *    그 호출은 트랜잭션 밖(백그라운드 스레드)이라 메서드에 @Transactional 을 직접 붙였다(삭제는 트랜잭션이 필요).
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    Optional<DeviceToken> findByToken(String token);

    @Query("select d.token from DeviceToken d where d.user.id = :userId")
    List<String> tokensOf(@Param("userId") long userId);

    @Transactional
    void deleteByToken(String token);
}
