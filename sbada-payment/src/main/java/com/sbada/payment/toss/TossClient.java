package com.sbada.payment.toss;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.payment.common.ApiException;
import com.sbada.payment.common.Json;

/**
 * 토스페이먼츠 코어 API 호출 (2026-11-25). 시크릿 키는 Basic 인증(시크릿키 + ":" 을 base64)으로만 보내고 로그에 남기지 않는다.
 *  - 승인    POST /v1/payments/confirm {paymentKey, orderId, amount}
 *  - 조회    GET  /v1/payments/orders/{orderId}   (가상계좌 입금 확인 · 웹훅 검증)
 *  - 취소    POST /v1/payments/{paymentKey}/cancel {cancelReason}
 * 지금은 테스트 키(test_sk_ · test_gsk_)만 받는다 — 실제 돈이 움직이는 라이브 키는 시작할 때 거절한다.
 */
@Component
public class TossClient {

    private static final Logger log = LoggerFactory.getLogger(TossClient.class);
    private static final String BASE = "https://api.tosspayments.com/v1";

    private final String clientKey;
    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TossClient(@Value("${sbada.toss.client-key:}") String clientKey,
                      @Value("${sbada.toss.secret-key:}") String secretKey) {
        String ck = clientKey == null ? "" : clientKey.trim();
        String sk = secretKey == null ? "" : secretKey.trim();
        if (ck.startsWith("live_") || sk.startsWith("live_")) {
            log.warn("토스 라이브 키는 아직 쓰지 않아요(실제 돈 이동) — 토스 결제를 끕니다. 테스트 키를 넣어 주세요.");
            ck = "";
            sk = "";
        }
        this.clientKey = ck;
        this.secretKey = sk;
    }

    public boolean configured() {
        return !clientKey.isEmpty() && !secretKey.isEmpty();
    }

    public String clientKey() {
        return clientKey;
    }

    public Map<String, Object> confirm(String paymentKey, String orderId, long amount) {
        return call("POST", "/payments/confirm",
                Json.write(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount)), orderId);
    }

    public Map<String, Object> getByOrderId(String orderId) {
        return call("GET", "/payments/orders/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8), null, orderId);
    }

    public Map<String, Object> cancel(String paymentKey, String reason) {
        return call("POST", "/payments/" + URLEncoder.encode(paymentKey, StandardCharsets.UTF_8) + "/cancel",
                Json.write(Map.of("cancelReason", reason == null || reason.isBlank() ? "교환 취소" : reason)), null);
    }

    private Map<String, Object> call(String method, String path, String body, String orderId) {
        if (!configured()) {
            throw new ApiException(503, "TOSS_NOT_CONFIGURED", "토스 테스트 키가 아직 없어요. 결제_택배_키_설정.command 로 넣어 주세요.");
        }
        String auth = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + auth).header("Content-Type", "application/json");
        if ("POST".equals(method)) {
            // 같은 요청이 두 번 가도 한 번만 처리되게(멱등 키). 승인은 주문번호로 고정
            b.header("Idempotency-Key", orderId != null ? "confirm-" + orderId : UUID.randomUUID().toString());
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("토스 API 연결 실패 {}: {}", path.replaceAll("/payments/[^/]+/cancel", "/payments/*/cancel"), e.getClass().getSimpleName());
            throw new ApiException(503, "TOSS_UNAVAILABLE", "토스페이먼츠에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        Map<String, Object> json;
        try {
            json = res.body() == null || res.body().isBlank() ? Map.of() : Json.readMap(res.body());
        } catch (RuntimeException e) {
            json = Map.of();
        }
        if (res.statusCode() / 100 != 2) {
            String code = String.valueOf(json.getOrDefault("code", "TOSS_ERROR"));
            String message = String.valueOf(json.getOrDefault("message", "토스페이먼츠가 요청을 거절했어요."));
            log.info("토스 API {} → HTTP {} {}", method, res.statusCode(), code);
            throw new ApiException(res.statusCode() == 401 ? 503 : 400, "TOSS_" + code, message);
        }
        return json;
    }
}
