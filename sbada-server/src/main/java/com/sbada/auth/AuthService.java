package com.sbada.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.ApiException;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;
import com.sbada.user.UserDtos;

/**
 * 회원가입·로그인의 업무 규칙(AuthController 가 부른다).
 *
 * [프로그래밍 설명]
 *  - @Service: 업무 로직을 담는 빈이라는 표시. 컴포넌트 스캔으로 자동 등록된다.
 *  - 생성자 주입으로 받는 세 가지 빈:
 *      AppUserRepository : DB 의 app_users 테이블 접근(Spring Data JPA 가 구현체를 자동 생성)
 *      PasswordEncoder   : SecurityConfig 에서 @Bean 으로 만든 BCryptPasswordEncoder
 *      JwtService        : 토큰 발급
 *  - @Transactional: 메서드 전체를 하나의 DB 트랜잭션으로 묶는다(정상 종료 → COMMIT, 런타임 예외 → ROLLBACK).
 *    readOnly = true 는 '읽기만 한다'는 힌트 → JPA 가 변경 감지(dirty checking)를 생략해 조금 빨라진다.
 */
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final KakaoUserClient kakao;
    private final TransactionTemplate tx;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwt,
                       KakaoUserClient kakao, TransactionTemplate tx) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.kakao = kakao;
        this.tx = tx;
    }

    /**
     * 회원가입 (POST /api/auth/signup).
     * 비밀번호는 원문을 저장하지 않고 BCrypt 해시만 저장한다.
     *  - BCrypt: 일부러 느리게 만든 단방향 해시 + 매번 다른 솔트(salt) → 같은 비밀번호라도 해시가 매번 다르고,
     *    DB 가 유출돼도 원래 비밀번호를 되돌리기 어렵다. 결과 예: $2a$10$jVhnHJ…(60자)
     */
    @Transactional
    public UserDtos.UserView signup(AuthDtos.SignupRequest input) {
        // ① 길이 검사: @Size 는 '글자 수'라서, 한글처럼 1글자가 3바이트인 경우를 따로 바이트로 확인한다
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            // BCrypt는 72바이트까지만 비교하므로 넘으면 거절한다(7장 6.1)
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "비밀번호가 너무 깁니다(UTF-8 72바이트 이하).");
        }
        // ② 중복 아이디 → 409 Conflict (자원의 현재 상태와 충돌). existsByUsername 은 메서드 이름으로 만든 쿼리:
        //    SELECT count(*) > 0 FROM app_users WHERE username = ?
        if (users.existsByUsername(input.username())) {
            throw ApiException.conflict("USERNAME_EXISTS", "이미 사용 중인 아이디입니다.");
        }
        // ③ 동네를 비워 보내면 기본값 "서울" (삼항 연산자: 조건 ? 참일 때 : 거짓일 때)
        String region = input.region() == null || input.region().isBlank() ? "서울" : input.region().trim();
        // ④ 엔티티 생성 → save() 가 INSERT 를 실행하고, DB 가 만든 id 가 채워진 엔티티를 돌려준다
        AppUser user = users.save(new AppUser(input.username(),
                passwordEncoder.encode(input.password()), input.nickname().trim(), region));
        // ⑤ 엔티티를 응답용 DTO 로 바꿔 돌려준다(passwordHash 는 DTO 에 없음)
        return UserDtos.UserView.from(user);
    }

    /**
     * 로그인 (POST /api/auth/login). 성공하면 JWT 를 발급한다.
     * 서버는 로그인 상태를 저장하지 않는다(STATELESS). 앱이 매 요청마다 토큰을 보내고, 서버는 그 서명만 검사한다.
     */
    @Transactional(readOnly = true)
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest input) {
        // findByUsername 은 Optional<AppUser> 를 돌려준다. orElse(null) = 없으면 null
        AppUser user = users.findByUsername(input.username().trim()).orElse(null);
        // 아이디가 없는 경우와 비밀번호가 틀린 경우를 같은 안내로 처리한다
        // passwordEncoder.matches(원문, 저장된 해시): 원문을 같은 솔트로 해시해 비교한다(해시를 되돌리는 게 아님)
        if (user == null || !passwordEncoder.matches(input.password(), user.getPasswordHash())) {
            throw new ApiException(401, "LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        // 토큰 안에는 사용자 번호(sub)만 들어간다 → 이후 요청에서 '누구인지'를 이 번호로 안다
        return new AuthDtos.LoginResponse(jwt.issue(user.getId()), "Bearer", jwt.ttlSeconds(),
                UserDtos.UserView.from(user));
    }

    /**
     * 카카오 로그인 (POST /api/auth/kakao). 처음이면 스바다 계정을 자동으로 만들고, 이미 있으면 그 계정으로 로그인.
     *  ① 카카오 서버에 토큰 확인 + 회원번호·닉네임 받기(KakaoUserClient) — 트랜잭션 밖(외부 호출이 느릴 수 있어서)
     *  ② kakao_id 로 계정 찾기 → 없으면 새 계정 만들기 — 이 부분만 짧은 트랜잭션(tx.execute)
     *  ③ 스바다 JWT 발급 → 아이디 로그인과 똑같은 응답
     * 새 계정은 onboarded=false 라서 앱이 관심사 고르기(온보딩) 화면으로 보낸다.
     */
    public AuthDtos.LoginResponse kakaoLogin(String kakaoAccessToken) {
        KakaoUserClient.KakaoProfile profile = kakao.fetch(kakaoAccessToken.trim());
        AppUser user = tx.execute(status -> users.findByKakaoId(profile.id())
                .orElseGet(() -> users.save(AppUser.forKakao(profile.id(), uniqueUsername(profile.id()),
                        passwordEncoder.encode(UUID.randomUUID().toString()), kakaoNickname(profile), "서울"))));
        return new AuthDtos.LoginResponse(jwt.issue(user.getId()), "Bearer", jwt.ttlSeconds(),
                UserDtos.UserView.from(user));
    }

    /** 카카오 계정의 스바다 아이디: kakao_회원번호 (이미 있으면 뒤에 _2, _3 …). 아이디 칸은 20자까지 */
    private String uniqueUsername(long kakaoId) {
        String base = cut("kakao_" + kakaoId, 20);
        String name = base;
        for (int i = 2; users.existsByUsername(name); i++) {
            String suffix = "_" + i;
            name = cut(base, 20 - suffix.length()) + suffix;
        }
        return name;
    }

    /** 닉네임은 2~12자(회원가입 규칙과 같게). 카카오 닉네임이 없거나 너무 짧으면 '카카오' + 회원번호 끝 4자리 */
    static String kakaoNickname(KakaoUserClient.KakaoProfile profile) {
        String n = profile.nickname() == null ? "" : profile.nickname().trim();
        if (n.codePointCount(0, n.length()) < 2) {
            String id = String.valueOf(profile.id());
            return "카카오" + id.substring(Math.max(0, id.length() - 4));
        }
        return n.codePointCount(0, n.length()) > 12 ? n.substring(0, n.offsetByCodePoints(0, 12)) : n;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
