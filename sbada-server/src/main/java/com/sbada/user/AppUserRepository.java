package com.sbada.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * app_users 테이블 접근.
 *
 * [프로그래밍 설명 — Spring Data JPA 리포지토리]
 *  - 인터페이스만 선언하면 스프링이 실행 중에 구현 클래스를 자동으로 만들어 빈으로 등록한다(SQL 을 직접 쓰지 않음).
 *  - JpaRepository<엔티티, 기본키 타입> 을 상속하면 기본 메서드가 생긴다:
 *      save(e)        INSERT 또는 UPDATE        findById(id)  SELECT … WHERE id = ?  (Optional)
 *      existsById(id) 있으면 true               findAll() / count() / delete(e) …
 *  - '메서드 이름으로 쿼리 만들기': 정해진 규칙으로 이름을 지으면 그대로 SQL 이 된다.
 *      findByUsername(u)   → SELECT * FROM app_users WHERE username = ?
 *      existsByUsername(u) → SELECT 1 … WHERE username = ? (있으면 true)
 *  - Optional<T>: '없을 수도 있는 값'을 감싸는 상자. null 대신 써서 '없을 때 처리'를 잊지 않게 한다.
 *      .orElse(null), .orElseThrow(() -> 예외), .isPresent() 등으로 꺼낸다.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 카카오 로그인: 카카오 회원번호로 연결된 스바다 계정 찾기 (SELECT … WHERE kakao_id = ?) */
    Optional<AppUser> findByKakaoId(Long kakaoId);
}
