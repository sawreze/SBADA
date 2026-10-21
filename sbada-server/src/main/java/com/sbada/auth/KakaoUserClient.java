package com.sbada.auth;

/**
 * 카카오 액세스 토큰 → 카카오 회원 정보.
 *
 * [프로그래밍 설명]
 *  - 인터페이스로 둔 이유: 실제 구현(KakaoApiClient)은 인터넷으로 카카오 서버를 부르는데,
 *    테스트에서는 카카오에 접속하지 않는 가짜 구현으로 바꿔 끼울 수 있다(의존성 역전 · 테스트 용이성).
 */
public interface KakaoUserClient {

    /**
     * 토큰이 우리 카카오 앱에서 발급된 진짜 토큰인지 확인하고 회원 정보를 돌려준다.
     * 실패하면 ApiException(401 KAKAO_TOKEN_INVALID 등)을 던진다.
     */
    KakaoProfile fetch(String accessToken);

    /** 카카오 회원번호(앱마다 고유)와 닉네임(동의하지 않았으면 null) */
    record KakaoProfile(long id, String nickname) {
    }
}
