package com.sbada.payment.events;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.payment.common.ApiException;
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
