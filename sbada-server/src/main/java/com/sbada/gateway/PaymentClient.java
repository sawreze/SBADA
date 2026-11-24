package com.sbada.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;
import com.sbada.common.Json;

/**
 * 메인 서버(교환 서비스) → 결제 서비스 내부 호출 (2026-11-25 금융 백엔드)
 *  POST {payment}/internal/escrow/release  교환 완료 → 받는 사람 지갑으로 정산
 *  POST {payment}/internal/escrow/refund   교환 거절 · 취소 → 낸 사람에게 환불
 * 인증은 X-Internal-Token(결제 서비스와 공유하는 비밀 파일). 결제 서비스가 꺼져 있으면 503 PAYMENT_SERVICE_DOWN.
 * 결제 서비스 쪽은 같은 호출이 두 번 와도 한 번만 반영한다(멱등) → 실패하면 그냥 다시 불러도 안전하다.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String paymentUrl;
    private final InternalToken token;

    public PaymentClient(@Value("${sbada.services.payment.url:http://127.0.0.1:8098}") String paymentUrl,
                         InternalToken token) {
        this.paymentUrl = paymentUrl.replaceAll("/+$", "");
        this.token = token;
    }

    /** 정산. 결과 예: {"status":"RELEASED", "amount":30000, …} */
    public Map<String, Object> release(long exchangeId) {
        return post("/internal/escrow/release", Map.of("exchangeId", exchangeId));
    }

    /** 환불. 결과 예: {"status":"REFUNDED", …} */
    public Map<String, Object> refund(long exchangeId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exchangeId", exchangeId);
        body.put("reason", reason);
        return post("/internal/escrow/refund", body);
    }

    /**
     * 환불을 '시도만' 한다(교환 취소 · 거절 직후, 커밋 뒤에 부름). 실패해도 예외를 던지지 않는다:
     * 결제 서비스의 대사 작업(1분마다)이 교환 상태를 보고 다시 환불하므로 돈이 묶여 있지 않는다.
     */
    public void refundQuietly(long exchangeId, String reason) {
        try {
            refund(exchangeId, reason);
        } catch (ApiException e) {
            log.warn("환불 요청 실패(결제 서비스가 나중에 다시 처리): 교환 {} — {}", exchangeId, e.getCode());
        }
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(paymentUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(InternalToken.HEADER, token.value())
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw down();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw down();
        }
        Map<String, Object> json = parse(res.body());
        if (res.statusCode() / 100 == 2) {
            return json;
        }
        // 결제 서비스의 오류(코드 · 메시지)를 그대로 앱에 전달한다. 예: 409 ALREADY_REFUNDED
        String code = json.get("code") instanceof String c ? c : "PAYMENT_FAILED";
        String message = json.get("message") instanceof String m ? m : "결제 서비스 처리에 실패했습니다.";
        int status = res.statusCode() >= 500 ? 503 : res.statusCode();
        throw new ApiException(status, code, message);
    }

    private static Map<String, Object> parse(String body) {
        try {
            return body == null || body.isBlank() ? Map.of() : Json.readMap(body);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static ApiException down() {
        return new ApiException(503, "PAYMENT_SERVICE_DOWN",
                "결제 서비스가 꺼져 있어요. 결제 서버를 켠 뒤 다시 시도해 주세요. (교환 · 채팅은 그대로 쓸 수 있어요)");
    }
}
