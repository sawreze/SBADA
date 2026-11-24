package com.sbada.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.ErrorResponse;
import com.sbada.common.Json;

import jakarta.servlet.http.HttpServletRequest;

/**
 * API 게이트웨이 — 물품 서버(sbada-item, :8099)로 넘기는 주소 (2026-11-25 MSA 2단계)
 *  앱은 지금처럼 메인 서버(8097) 한 곳만 부르고, 아래 주소는 그대로 물품 서버에 전달한다(본문 · 로그인 토큰 그대로).
 *   /api/items/**  /api/images/**  /api/ai/**  /api/categories  /api/feed/**  /api/me/items  /api/me/likes
 *   /graphql (물품 목록 조회 GraphQL)
 *  - 사진 올리기(multipart)도 바이트 그대로 넘긴다(메인 서버는 multipart 를 해석하지 않음: spring.servlet.multipart.enabled=false)
 *  - 로그인 확인은 물품 서버가 같은 규칙으로 한다(공개: 목록 · 상세 · 사진 · 카테고리, 나머지는 토큰 필요)
 *  - 물품 서버가 꺼져 있으면 503 ITEM_SERVICE_DOWN (채팅 · 알림 · 교환 목록은 메인 서버라 그대로 동작)
 */
@RestController
public class ItemGatewayController {

    private static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CACHE_CONTROL,
            HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.LAST_MODIFIED);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String itemUrl;

    public ItemGatewayController(@Value("${sbada.services.item.url:http://127.0.0.1:8099}") String itemUrl) {
        this.itemUrl = itemUrl.replaceAll("/+$", "");
    }

    @RequestMapping({"/api/items", "/api/items/**", "/api/images", "/api/images/**", "/api/ai/**",
            "/api/categories", "/api/feed", "/api/feed/**", "/api/me/items", "/api/me/likes", "/graphql"})
    public ResponseEntity<byte[]> forward(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String query = query(request);
        URI target = URI.create(itemUrl + request.getRequestURI() + (query == null ? "" : "?" + query));
        // AI 분석은 오래 걸릴 수 있다(물품 서버의 AI 제한 시간 90초 + 여유)
        Duration timeout = request.getRequestURI().startsWith("/api/ai") || "POST".equals(request.getMethod())
                && request.getRequestURI().equals("/api/items") ? Duration.ofSeconds(120) : Duration.ofSeconds(15);
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(timeout)
                .method(request.getMethod(), body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        copy(request, b, HttpHeaders.AUTHORIZATION);
        copy(request, b, HttpHeaders.CONTENT_TYPE);
        copy(request, b, HttpHeaders.ACCEPT_LANGUAGE);
        copy(request, b, HttpHeaders.IF_NONE_MATCH);
        b.header("X-Forwarded-For", request.getRemoteAddr());
        try {
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            ResponseEntity.BodyBuilder out = ResponseEntity.status(res.statusCode());
            for (String h : RESPONSE_HEADERS) {
                res.headers().firstValue(h).ifPresent(v -> {
                    if (HttpHeaders.CONTENT_TYPE.equals(h)) {
                        out.contentType(MediaType.parseMediaType(jsonUtf8(h, v)));
                    } else {
                        out.header(h, v);
                    }
                });
            }
            return out.body(res.body());
        } catch (IOException e) {
            return down();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down();
        }
    }

    /** 주소 ? 뒤의 값. (테스트 · 일부 서블릿 환경에서 원문이 없으면 GET 파라미터로 다시 만든다) */
    private static String query(HttpServletRequest request) {
        String q = request.getQueryString();
        if (q != null || !"GET".equals(request.getMethod()) || request.getParameterMap().isEmpty()) {
            return q;
        }
        StringBuilder b = new StringBuilder();
        request.getParameterMap().forEach((k, vs) -> {
            for (String v : vs) {
                b.append(b.isEmpty() ? "" : "&").append(java.net.URLEncoder.encode(k, StandardCharsets.UTF_8))
                        .append('=').append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return b.toString();
    }

    /** JSON 은 UTF-8 이라고 분명히 적어 준다(글자 깨짐 방지: 한글 물건 이름) */
    private static String jsonUtf8(String header, String value) {
        if (HttpHeaders.CONTENT_TYPE.equals(header) && value.startsWith("application/json") && !value.contains("charset")) {
            return value + ";charset=UTF-8";
        }
        return value;
    }

    private static void copy(HttpServletRequest from, HttpRequest.Builder to, String name) {
        String v = from.getHeader(name);
        if (v != null && !v.isBlank()) {
            to.header(name, v);
        }
    }

    private static ResponseEntity<byte[]> down() {
        byte[] json = Json.write(ErrorResponse.of("ITEM_SERVICE_DOWN",
                "물품 서버가 꺼져 있어요. 물품 서버를 켠 뒤 다시 시도해 주세요. (채팅 · 알림은 그대로 쓸 수 있어요)"))
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
