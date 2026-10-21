package com.sbada.auth;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.user.UserDtos;

import jakarta.validation.Valid;

/**
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 회원가입 · 로그인 API   (기본 주소: /api/auth, 로그인 없이 호출 가능) │
 * └──────────────────────────────────────────────────────────────┘
 *  POST /api/auth/signup  → 201 Created + 가입한 사용자 정보
 *  POST /api/auth/login   → 200 OK + JWT 액세스 토큰
 *
 * [프로그래밍 설명 — Controller 의 공통 문법(다른 Controller 도 같다)]
 *  - @RestController = @Controller + @ResponseBody.
 *    메서드가 돌려준 객체를 '화면(HTML)'이 아니라 JSON 으로 바꿔 HTTP 응답 본문에 쓴다(Jackson 이 변환).
 *  - @RequestMapping("/api/auth"): 이 클래스 안 모든 주소 앞에 붙는 공통 경로.
 *  - @PostMapping("/signup"): HTTP POST + /api/auth/signup 요청이 오면 이 메서드를 실행한다.
 *  - @RequestBody: 요청 본문 JSON 을 파라미터 타입(record)으로 바꿔 준다.
 *    JSON 키 이름 = record 필드 이름. 없는 키를 보내면 400 UNKNOWN_FIELD(GlobalExceptionHandler).
 *  - @Valid: 변환 직후 record 필드의 검증 애너테이션(@NotBlank, @Size …)을 검사한다. 실패 → 400 VALIDATION_FAILED.
 *  - 생성자 주입(DI): AuthService 를 new 로 만들지 않는다. 스프링이 만들어 둔 AuthService 빈을 생성자에 넣어 준다.
 *    필드가 final 이라 한 번 정해지면 바뀌지 않고, 테스트할 때 가짜 객체로 바꿔 넣기도 쉽다.
 *  - Controller 는 '요청을 받고 응답 모양을 정하는 일'만 하고, 실제 규칙(중복 아이디, 비밀번호 확인)은 Service 가 한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /**
     * ■ 회원가입   POST /api/auth/signup   (공개)
     *
     * 요청 본문(JSON) — AuthDtos.SignupRequest
     *   {
     *     "username": "minji2",          // 필수, 영문 소문자·숫자·밑줄 4~20자
     *     "password": "study-1234",      // 필수, 8~64자 (UTF-8 72바이트 이하)
     *     "nickname": "민지",            // 필수, 2~12자
     *     "region":   "마포구 망원동"     // 선택, 20자 이하. 비우면 "서울"
     *   }
     *
     * 처리 흐름
     *   ① JSON → SignupRequest 변환 + @Valid 검사
     *   ② AuthService.signup: 비밀번호 길이 확인 → 아이디 중복 확인 → BCrypt 로 비밀번호 해시 → DB 저장
     *   ③ 201 Created 응답. Location 헤더에 새 자원 주소(/api/users/{id})를 넣는 것이 REST 관례다.
     *
     * 응답 201 — UserDtos.UserView (비밀번호 해시는 절대 들어가지 않는다)
     *   {"id": 5, "username": "minji2", "nickname": "민지", "region": "마포구 망원동", "onboarded": false}
     *
     * 오류
     *   400 VALIDATION_FAILED  입력 형식이 틀림(errors 에 필드별 이유)
     *   400 PASSWORD_TOO_LONG  비밀번호가 72바이트 초과
     *   409 USERNAME_EXISTS    이미 있는 아이디
     *
     * ResponseEntity.created(URI) → 상태 201 + Location 헤더, .body(user) → 응답 본문.
     * (가입 직후 앱은 같은 아이디·비밀번호로 /login 을 불러 토큰을 받는다)
     */
    @PostMapping("/signup")
    public ResponseEntity<UserDtos.UserView> signup(@Valid @RequestBody AuthDtos.SignupRequest input) {
        UserDtos.UserView user = service.signup(input);
        return ResponseEntity.created(URI.create("/api/users/" + user.id())).body(user);
    }

    /**
     * ■ 로그인   POST /api/auth/login   (공개)
     *
     * 요청 본문(JSON) — AuthDtos.LoginRequest
     *   {"username": "minji", "password": "sbada1234"}
     *
     * 처리 흐름
     *   ① 아이디로 사용자 조회 → ② BCrypt 로 비밀번호 비교(passwordEncoder.matches)
     *   ③ 맞으면 JwtService.issue(userId) 로 2시간짜리 JWT 를 만들어 돌려준다.
     *
     * 응답 200 — AuthDtos.LoginResponse
     *   {
     *     "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzYmFkYSIsInN1YiI6IjEi…",
     *     "tokenType": "Bearer",
     *     "expiresIn": 7200,                       // 초 단위(2시간)
     *     "user": {"id":1,"username":"minji","nickname":"민지","region":"마포구 망원동","onboarded":true}
     *   }
     *   → 앱은 이후 모든 보호 API 요청에 헤더  Authorization: Bearer {accessToken}  를 붙인다.
     *
     * 오류
     *   400 VALIDATION_FAILED  아이디/비밀번호가 비어 있음
     *   401 LOGIN_FAILED       아이디가 없거나 비밀번호가 틀림(어느 쪽인지 알려 주지 않음 → 아이디 존재 여부 노출 방지)
     *
     * 반환 타입이 ResponseEntity 가 아니라 DTO 자체면 상태 코드는 기본값 200 이다.
     */
    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@Valid @RequestBody AuthDtos.LoginRequest input) {
        return service.login(input);
    }

    /**
     * ■ 카카오 로그인   POST /api/auth/kakao   (공개)
     *
     * 전체 흐름
     *   앱 ──(카카오 SDK: 카카오톡 또는 카카오계정 로그인)──▶ 카카오 ──▶ 앱이 '카카오 액세스 토큰'을 받음
     *   앱 ──POST /api/auth/kakao {accessToken}──▶ 스바다 서버 ──(토큰 확인)──▶ 카카오 API
     *   스바다 서버 ──▶ 앱: 스바다 JWT (이후는 아이디 로그인과 완전히 같음)
     *
     * 요청 본문(JSON) — AuthDtos.KakaoLoginRequest
     *   {"accessToken": "카카오가_앱에_준_토큰"}
     *
     * 처리 흐름 (AuthService.kakaoLogin)
     *   ① 카카오에 토큰 정보 조회 → 우리 앱(app_id)의 토큰인지 확인 ② 카카오 회원번호·닉네임 조회
     *   ③ app_users.kakao_id 로 계정 찾기 → 없으면 새로 만들기(아이디 kakao_회원번호, 닉네임 = 카카오 닉네임, 동네 서울)
     *   ④ 스바다 JWT 발급
     *
     * 응답 200 — AuthDtos.LoginResponse (POST /api/auth/login 과 같은 모양)
     *   {"accessToken":"eyJ…","tokenType":"Bearer","expiresIn":7200,
     *    "user":{"id":5,"username":"kakao_1234567890","nickname":"해인","region":"서울","onboarded":false}}
     *   → onboarded=false 면 앱이 온보딩 화면으로 보낸다.
     *
     * 오류
     *   400 VALIDATION_FAILED     accessToken 이 비어 있음
     *   401 KAKAO_TOKEN_INVALID   카카오가 토큰을 거절(만료·위조)
     *   401 KAKAO_APP_MISMATCH    다른 카카오 앱에서 받은 토큰
     *   502 KAKAO_UNAVAILABLE     카카오 서버 연결 실패
     *   503 KAKAO_NOT_CONFIGURED  서버에 sbada.auth.kakao.app-id 설정이 없음
     *
     * 카카오 토큰은 확인에만 쓰고 저장하지 않는다(로그에도 남기지 않음).
     */
    @PostMapping("/kakao")
    public AuthDtos.LoginResponse kakao(@Valid @RequestBody AuthDtos.KakaoLoginRequest input) {
        return service.kakaoLogin(input.accessToken());
    }
}
