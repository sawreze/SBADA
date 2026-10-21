package com.sbada.web;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.ai.AiDtos;
import com.sbada.ai.AiPriceService;
import com.sbada.item.ItemRepository;
import com.sbada.realtime.RealtimeHub;
import com.sbada.user.AppUserRepository;

/**
 * 로컬호스트 실행 확인용: 브라우저에서 http://127.0.0.1:8097/ 을 열면 서버 상태가 보인다.
 *
 *  GET /api/health   공개  서버 상태 JSON
 *  GET /             공개  서버 상태 HTML 페이지(브라우저용)
 */
@RestController
public class HealthController {

    private final Instant startedAt = Instant.now(); // 이 빈이 만들어진 시각 = 서버 시작 시각
    private final AiPriceService ai;
    private final RealtimeHub hub;
    private final AppUserRepository users;
    private final ItemRepository items;

    public HealthController(AiPriceService ai, RealtimeHub hub, AppUserRepository users, ItemRepository items) {
        this.ai = ai;
        this.hub = hub;
        this.users = users;
        this.items = items;
    }

    /**
     * ■ 서버 상태   GET /api/health   (공개)
     * 응답 200
     *   {"status":"UP","app":"sbada-server","uptimeSeconds":3600,"users":4,"items":16,
     *    "websocketOnlineUsers":1,"ai":{AiStatus…}}
     * smoke 테스트·앱 시작 화면이 '서버가 켜져 있는지' 확인할 때 쓴다.
     * LinkedHashMap 을 쓴 이유: JSON 키 순서를 넣은 순서대로 유지(읽기 쉽게).
     */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        AiDtos.AiStatus status = ai.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app", "sbada-server");
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        body.put("users", users.count());
        body.put("items", items.count());
        body.put("websocketOnlineUsers", hub.onlineUsers());
        body.put("ai", status);
        return body;
    }

    /**
     * ■ 상태 페이지   GET /   (공개, HTML)
     * produces = TEXT_HTML_VALUE: 응답 Content-Type 을 text/html 로 → 브라우저가 웹 페이지로 보여 준다.
     * 텍스트 블록(""" … """) + formatted(…) 로 %d·%s 자리에 값을 채운다(String.format 과 같음).
     */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        AiDtos.AiStatus status = ai.status();
        return """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>스바다 서버</title>
                <style>
                 body{font-family:system-ui,'Malgun Gothic',sans-serif;background:#f4f6fa;color:#191f28;margin:0;padding:32px}
                 .card{background:#fff;border-radius:20px;padding:28px;max-width:720px;margin:auto;box-shadow:0 4px 20px rgba(0,0,0,.06)}
                 h1{margin:0 0 4px;font-size:28px} .ok{color:#12b886;font-weight:700}
                 code{background:#eef2ff;padding:2px 6px;border-radius:6px}
                 li{margin:6px 0}
                </style></head><body><div class="card">
                <h1>🌊 스바다 서버</h1>
                <p class="ok">● 실행 중 (UP)</p>
                <ul>
                 <li>회원 %d명 · 물건 %d개 · WebSocket 접속자 %d명</li>
                 <li>AI 가격 분석: <b>%s</b> (설정: %s, Gemma 모델: %s / %s)</li>
                 <li>상태 JSON: <a href="/api/health">/api/health</a></li>
                 <li>공개 목록: <a href="/api/items">/api/items</a> · 카테고리: <a href="/api/categories">/api/categories</a></li>
                 <li>WebSocket: <code>ws://127.0.0.1:8097/ws?token=JWT</code> (에뮬레이터는 10.0.2.2)</li>
                 <li>H2 콘솔(dev 프로필): <a href="/h2-console">/h2-console</a></li>
                </ul></div></body></html>
                """.formatted(users.count(), items.count(), hub.onlineUsers(), status.active(), status.setting(),
                status.googleModel(), status.ollamaModel());
    }
}
