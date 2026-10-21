package com.sbada.auth;

import com.sbada.user.UserDtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입·로그인 API 의 요청/응답 JSON 모양(DTO = Data Transfer Object).
 *
 * [프로그래밍 설명]
 *  - 엔티티(AppUser)를 그대로 받거나 돌려주지 않고 DTO 를 따로 두는 이유:
 *      ① 비밀번호 해시 같은 내부 필드가 응답에 새지 않게 한다.
 *      ② 클라이언트가 보낼 수 있는 필드를 딱 정해 둔다(그 밖의 필드는 400 UNKNOWN_FIELD).
 *      ③ DB 구조가 바뀌어도 API 모양은 유지할 수 있다.
 *  - 검증 애너테이션(Jakarta Bean Validation): Controller 파라미터에 @Valid 가 있을 때만 검사된다.
 *      @NotBlank : null·빈 문자열·공백만 있는 문자열 금지
 *      @Size     : 문자열 길이(또는 목록 크기) 범위
 *      @Pattern  : 정규식. ^[a-z0-9_]{4,20}$ = 처음(^)부터 끝($)까지 소문자·숫자·밑줄만 4~20개
 *    message 는 검증 실패 시 응답 errors 에 들어갈 안내 문구다.
 *  - 여러 record 를 한 파일에 모으려고 바깥 클래스(AuthDtos) 안에 static 중첩 record 로 두었다.
 *    사용할 때는 AuthDtos.SignupRequest 처럼 부른다.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** POST /api/auth/signup 요청 본문 */
    public record SignupRequest(
            @NotBlank(message = "아이디를 입력해 주세요.")
            @Pattern(regexp = "^[a-z0-9_]{4,20}$", message = "아이디는 영문 소문자·숫자·밑줄 4~20자입니다.")
            String username,
            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다.")
            String password,
            @NotBlank(message = "닉네임을 입력해 주세요.")
            @Size(min = 2, max = 12, message = "닉네임은 2~12자입니다.")
            String nickname,
            @Size(max = 20, message = "동네 이름은 20자 이하입니다.")
            String region) { // @NotBlank 가 없으므로 선택 항목(null 가능)
    }

    /** POST /api/auth/login 요청 본문 */
    public record LoginRequest(
            @NotBlank(message = "아이디를 입력해 주세요.") String username,
            @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {
    }

    /**
     * 7장 6.3의 로그인 응답 모양: accessToken · tokenType · expiresIn(초) · user
     * tokenType "Bearer" = '이 토큰을 가진 사람(bearer)을 인증된 사용자로 본다'는 OAuth 2.0 표준 방식의 이름.
     */
    /**
     * POST /api/auth/kakao 요청 본문: 앱이 카카오 SDK 로 로그인해서 받은 '카카오 액세스 토큰'
     *   {"accessToken": "카카오가 준 토큰"}
     */
    public record KakaoLoginRequest(
            @NotBlank(message = "카카오 토큰이 없습니다.") @Size(max = 512, message = "카카오 토큰이 너무 깁니다.")
            String accessToken) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserDtos.UserView user) {
    }
}
