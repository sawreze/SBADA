package com.sbada.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;
import com.sbada.common.Json;

/**
 * 카카오 서버에 토큰을 확인하고 회원 정보를 받아 온다.
 *
 * ■ 서버 → 카카오 (외부 API 호출 2번)
 *   ① GET https://kapi.kakao.com/v1/user/access_token_info     헤더 Authorization: Bearer {카카오 토큰}
 *      응답 {"id": 1234567890, "expires_in": 7199, "app_id": 123456}
 *      → app_id 가 우리 앱 ID 와 같은지 확인한다. 다른 카카오 앱에서 받은 토큰을 가져와
 *        우리 서버에 로그인하는 것(토큰 바꿔치기)을 막는 핵심 검사.
 *   ② GET https://kapi.kakao.com/v2/user/me                    헤더 Authorization: Bearer {카카오 토큰}
 *      응답 {"id": 1234567890, "kakao_account": {"profile": {"nickname": "해인"}}, "properties": {"nickname": "해인"}}
 *
 * 설정: sbada.auth.kakao.app-id = 카카오 콘솔 앱 정보의 '앱 ID'(숫자). 비밀값은 아니지만 PC마다 다르므로
 *       application-local.properties 또는 환경 변수 KAKAO_APP_ID 에 둔다.
 *
 * [프로그래밍 설명]
 *  - 앱이 보낸 "나 카카오 회원 123번이야" 라는 말은 믿지 않는다. 토큰을 서버가 직접 카카오에 물어봐서
 *    카카오가 확인해 준 id 만 쓴다(클라이언트 입력 불신 원칙 — AI 가격을 서버가 복사하는 것과 같은 생각).
 *  - 카카오가 401 을 주면 토큰이 만료·위조된 것 → 우리도 401. 네트워크 오류는 502(바깥 서버 문제).
 */
@Component
public class KakaoApiClient implements KakaoUserClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoApiClient.class);
    private static final String BASE = "https://kapi.kakao.com";

    private final String appId;
    private final HttpClient http;

    public KakaoApiClient(@Value("${sbada.auth.kakao.app-id:}") String appId) {
        this.appId = appId == null ? "" : appId.trim().replace("\"", "").replace("'", "");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public KakaoProfile fetch(String accessToken) {
        if (appId.isEmpty()) {
            throw new ApiException(503, "KAKAO_NOT_CONFIGURED",
                    "서버에 카카오 앱 ID(sbada.auth.kakao.app-id)가 설정되지 않았습니다.");
        }
        // ① 토큰 정보: 어느 앱의 토큰인지 확인
        Map<String, Object> info = get("/v1/user/access_token_info", accessToken);
        if (!appId.equals(String.valueOf(asLong(info.get("app_id"))))) {
            log.warn("다른 카카오 앱의 토큰으로 로그인 시도(app_id={})", info.get("app_id"));
            throw new ApiException(401, "KAKAO_APP_MISMATCH", "스바다 카카오 앱에서 받은 로그인이 아닙니다.");
        }
        // ② 회원 정보: 회원번호 + 닉네임
        Map<String, Object> me = get("/v2/user/me", accessToken);
        Long id = asLong(me.get("id"));
        if (id == null) {
            throw new ApiException(502, "KAKAO_BAD_RESPONSE", "카카오 응답에 회원번호가 없습니다.");
        }
        return new KakaoProfile(id, nickname(me));
    }

    /** kakao_account.profile.nickname → 없으면 properties.nickname → 없으면 null */
    static String nickname(Map<String, Object> me) {
        if (me.get("kakao_account") instanceof Map<?, ?> account
                && account.get("profile") instanceof Map<?, ?> profile
                && profile.get("nickname") instanceof String n && !n.isBlank()) {
            return n;
        }
        if (me.get("properties") instanceof Map<?, ?> props && props.get("nickname") instanceof String n
                && !n.isBlank()) {
            return n;
        }
        return null;
    }

    private Map<String, Object> get(String path, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("카카오 API 호출 실패 {}: {}", path, e.getMessage());
            throw new ApiException(502, "KAKAO_UNAVAILABLE", "카카오 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        int status = response.statusCode();
        if (status == 401 || status == 400) {
            throw new ApiException(401, "KAKAO_TOKEN_INVALID", "카카오 로그인이 만료되었거나 올바르지 않습니다.");
        }
        if (status / 100 != 2) {
            log.warn("카카오 API {} 응답 HTTP {}", path, status);
            throw new ApiException(502, "KAKAO_UNAVAILABLE", "카카오 서버 응답이 올바르지 않습니다(HTTP " + status + ").");
        }
        return Json.readMap(response.body());
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
