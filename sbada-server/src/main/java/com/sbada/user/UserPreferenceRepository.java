package com.sbada.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sbada.common.Category;

/**
 * user_preferences 테이블 접근.
 *
 * [프로그래밍 설명]
 *  - findByUserIdOrderByWeightDesc: 이름 규칙 쿼리. 'User 의 Id' 처럼 연관 엔티티의 필드도 이어 붙일 수 있다.
 *      → SELECT * FROM user_preferences WHERE user_id = ? ORDER BY weight DESC
 *  - @Query: 이름 규칙으로 표현하기 어려울 때 JPQL 을 직접 쓴다.
 *    JPQL 은 테이블이 아니라 '엔티티와 필드'를 대상으로 쓰는 쿼리 언어다(UserPreference p, p.user.id).
 *    :userIds 같은 이름 붙은 파라미터에는 @Param("userIds") 로 값을 넣는다.
 */
public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    List<UserPreference> findByUserIdOrderByWeightDesc(Long userId);

    Optional<UserPreference> findByUserIdAndCategory(Long userId, Category category);

    /** 여러 사용자의 선호도를 한 번에 읽는다(추천 계산에서 '상대도 내 물건을 원하는가' 점수용). */
    @Query("select p from UserPreference p where p.user.id in :userIds")
    List<UserPreference> findByUserIds(@Param("userIds") Collection<Long> userIds);

    /** 이 카테고리에 일정 이상 관심 있는 사람들(새 물건 매칭 알림 대상 찾기) */
    @Query("select p from UserPreference p where p.category = :category and p.weight >= :minWeight")
    List<UserPreference> findInterested(@Param("category") Category category, @Param("minWeight") double minWeight);
}
