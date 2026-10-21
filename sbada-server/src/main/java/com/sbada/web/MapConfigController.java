package com.sbada.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱이 지도를 그릴 때 필요한 설정을 내려 준다.
 *  - 카카오맵 JavaScript 키는 Git 에 올리지 않는 application-local.properties(sbada.map.kakao-js-key)나
 *    환경 변수 KAKAO_JS_KEY 에만 둔다. 앱 소스에 키를 넣지 않으려고 서버가 대신 전달한다.
 *  - JavaScript 키는 원래 브라우저(WebView)에 그대로 실리는 값이고, 카카오 콘솔에 등록한 도메인에서만 동작한다.
 *    (REST API 키·Client Secret 같은 진짜 비밀값은 여기서 절대 내보내지 않는다.)
 *  - 키가 없으면 configured=false → 앱은 직접 그린 약도로 대신 보여 준다.
 *
 * 지도 종류(sbada.map.provider):
 *  - osm(기본): OpenStreetMap 지도 + Leaflet. 키 · 요금 없음 → 항상 configured=true, kakaoJsKey=null
 *  - kakao    : 카카오맵(유료 전환 · 콘솔의 카카오맵 '사용 설정' 필요). 키가 있어야 configured=true
 * 응답 예: {"provider":"osm","configured":true,"kakaoJsKey":null}
 */
@RestController
public class MapConfigController {

    private final String provider;
    private final String kakaoJsKey;

    public MapConfigController(@Value("${sbada.map.provider:osm}") String provider,
                               @Value("${sbada.map.kakao-js-key:}") String kakaoJsKey) {
        this.provider = "kakao".equalsIgnoreCase(clean(provider)) ? "kakao" : "osm";
        this.kakaoJsKey = clean(kakaoJsKey);
    }

    @GetMapping("/api/config/map")
    public Map<String, Object> mapConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean kakao = provider.equals("kakao");
        body.put("provider", provider);
        body.put("configured", !kakao || !kakaoJsKey.isEmpty());
        body.put("kakaoJsKey", kakao && !kakaoJsKey.isEmpty() ? kakaoJsKey : null);
        return body;
    }

    /** 설정 파일에 "따옴표"로 감싸 넣어도 동작하도록 앞뒤 공백·따옴표를 지운다. */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String k = raw.trim();
        while (k.length() >= 2 && ((k.startsWith("\"") && k.endsWith("\"")) || (k.startsWith("'") && k.endsWith("'")))) {
            k = k.substring(1, k.length() - 1).trim();
        }
        return k;
    }
}
