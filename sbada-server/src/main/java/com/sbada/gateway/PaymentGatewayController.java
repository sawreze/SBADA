package com.sbada.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
 * API 게이트웨이(경로 라우팅) — MSA 1단계
 *
 *   앱 ──▶ 메인 서버 :8097 ──┬─ /api/items · /api/exchanges · …  → 이 서버가 직접 처리(회원 · 물건 · 교환 · 채팅 · 알림)
 *                            └─ /api/wallet/** · /api/payments/** → 결제 서비스 :8098 로 그대로 전달
 *
 *  - 로그인 검사(JWT)는 Spring Security 가 여기서 먼저 한다 → 토큰 없는 요청은 결제 서비스까지 가지 않는다.
 *    결제 서비스도 같은 토큰을 한 번 더 검증한다(서비스가 게이트웨이만 믿지 않도록 = 제로 트러스트).
 *  - 결제 서비스가 꺼져 있으면 503 PAYMENT_SERVICE_DOWN. 나머지 기능(교환 · 채팅)은 그대로 동작 = 장애 격리.
 *  - 서비스가 늘면 이 클래스를 Spring Cloud Gateway · Nginx 같은 독립 게이트웨이로 바꾸면 된다(앱은 그대로).
 */
@RestController
public class PaymentGatewayController {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String paymentUrl;

    public PaymentGatewayController(@Value("${sbada.services.payment.url:http://127.0.0.1:8098}") String paymentUrl) {
        this.paymentUrl = paymentUrl.replaceAll("/+$", "");
    }

    @RequestMapping({"/api/wallet", "/api/wallet/**", "/api/payments/**"})
    public ResponseEntity<byte[]> forward(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String query = request.getQueryString();
        URI target = URI.create(paymentUrl + request.getRequestURI() + (query == null ? "" : "?" + query));
        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                .method(request.getMethod(), body == null || body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        copy(request, b, HttpHeaders.AUTHORIZATION);
        copy(request, b, HttpHeaders.CONTENT_TYPE);
        copy(request, b, HttpHeaders.ACCEPT_LANGUAGE);
        b.header("X-Forwarded-For", request.getRemoteAddr());
        try {
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            ResponseEntity.BodyBuilder out = ResponseEntity.status(res.statusCode());
            res.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent(ct -> out.header(HttpHeaders.CONTENT_TYPE, ct));
            return out.body(res.body());
        } catch (IOException e) {
            return down();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down();
        }
    }

    private static void copy(HttpServletRequest from, HttpRequest.Builder to, String name) {
        String v = from.getHeader(name);
        if (v != null && !v.isBlank()) {
            to.header(name, v);
        }
    }

    private static ResponseEntity<byte[]> down() {
        byte[] json = Json.write(ErrorResponse.of("PAYMENT_SERVICE_DOWN",
                "결제 서비스가 꺼져 있어요. 결제 서버를 켠 뒤 다시 시도해 주세요. (교환 · 채팅은 그대로 쓸 수 있어요)"))
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
