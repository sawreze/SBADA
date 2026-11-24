package com.sbada.payment.events;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.payment.common.ApiException;
import com.sbada.payment.common.Json;
import com.sbada.payment.common.SecretFiles;

/**
 * 메인 서버(회원 · 알림 서비스)와 이야기하는 클라이언트. 내부 주소 /internal/** 만 쓴다.
 * 서비스끼리는 'X-Internal-Token' 비밀 토큰으로 서로를 확인한다(앱 · 외부에서는 부를 수 없음).
 */
@Component
public class CoreClient {

    public static final String HEADER = "X-Internal-Token";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String token;
    private final boolean verifyUsers;

    public CoreClient(@Value("${sbada.core.url}") String baseUrl,
                      @Value("${sbada.internal.token-file}") String tokenFile,
                      @Value("${sbada.core.verify-users:true}") boolean verifyUsers) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = Base64.getEncoder().encodeToString(SecretFiles.loadOrCreate(Path.of(tokenFile), 32));
        this.verifyUsers = verifyUsers;
    }

    /** 메인 서버 확인을 켰는지(테스트에서는 끄고 결제 서비스만 따로 시험한다) */
    public boolean verifies() {
        return verifyUsers;
    }

    /** 메인 서버 → 결제 서비스 내부 호출(/internal/escrow/**)의 토큰 확인. 걸린 시간으로 추측하지 못하게 isEqual */
    public void checkInternal(String given) {
        if (given == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                given.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(401, "INTERNAL_AUTH_FAILED", "내부 서비스 인증에 실패했습니다.");
        }
    }

    /**
     * 교환 서비스(메인 서버)가 아는 교환 정보. 추가 금액 · 누가 내는지는 메인 서버가 정하고, 결제 서비스는 그대로 따른다.
     *  status: REQUESTED · ACCEPTED · REJECTED · CANCELED
     *  payerId · payeeId: 추가 금액 조건이 없으면 null
     */
    public record ExchangeInfo(long id, String status, long extraAmount, Long payerId, Long payeeId,
                               String paymentStatus) {
    }

    /** 교환 정보 묻기. 없으면 Optional.empty(), 메인 서버가 꺼져 있으면 503 */
    public Optional<ExchangeInfo> findExchange(long exchangeId) {
        HttpResponse<String> res;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/exchanges/" + exchangeId))
                    .timeout(Duration.ofSeconds(5)).header(HEADER, token).GET().build();
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ApiException(503, "CORE_SERVICE_DOWN", "교환 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "CORE_SERVICE_DOWN", "교환 서비스에 연결할 수 없습니다.");
        }
        if (res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() / 100 != 2) {
            throw new ApiException(503, "CORE_SERVICE_DOWN", "교환 서비스 응답이 올바르지 않습니다.");
        }
        Map<String, Object> m = Json.readMap(res.body());
        return Optional.of(new ExchangeInfo(num(m.get("id")), String.valueOf(m.get("status")),
                num(m.get("extraAmount")), optNum(m.get("payerId")), optNum(m.get("payeeId")),
                String.valueOf(m.get("paymentStatus"))));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Long optNum(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    /** 이벤트 전달. 2xx 면 true, 메인 서버가 꺼져 있거나 실패면 false(아웃박스가 나중에 다시 보냄) */
    public boolean sendEvent(String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/events"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(HEADER, token)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 추가 요금을 받을 사람이 실제 회원인지 회원 서비스에 묻는다 */
    public void requireUser(long userId) {
        if (!verifyUsers) {
            return;
        }
        int status;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/users/" + userId))
                    .timeout(Duration.ofSeconds(5)).header(HEADER, token).GET().build();
            status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new ApiException(503, "CORE_SERVICE_DOWN", "회원 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "CORE_SERVICE_DOWN", "회원 서비스에 연결할 수 없습니다.");
        }
        if (status == 404) {
            throw ApiException.notFound("USER_NOT_FOUND", "받는 사람을 찾을 수 없습니다.");
        }
        if (status / 100 != 2) {
            throw new ApiException(503, "CORE_SERVICE_DOWN", "회원 서비스 응답이 올바르지 않습니다.");
        }
    }
}
