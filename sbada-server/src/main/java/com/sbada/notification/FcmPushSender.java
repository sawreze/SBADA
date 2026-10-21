package com.sbada.notification;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.common.Json;

import io.jsonwebtoken.Jwts;

/**
 * FCM HTTP v1 푸시(1장 그림 1-10: 서버 → 운영체제 회사의 알림 서버 → 폰).
 * 앱이 꺼져 있어 WebSocket이 끊긴 사용자에게만 보낸다.
 *
 * 사용 조건: Firebase 프로젝트의 서비스 계정 JSON(비공개 키)을 PC에 두고
 *   sbada.push.fcm.enabled=true, sbada.push.fcm.service-account-file=경로 를 application-local.properties 에 적는다.
 * 키 파일은 Git·공개 레포에 절대 올리지 않는다.
 *
 * [프로그래밍 설명 — FCM HTTP v1 호출 순서]
 *  ① 서비스 계정 JSON 에서 프로젝트 ID·이메일·RSA 비밀 키를 읽는다(account()).
 *  ② 그 비밀 키로 RS256 서명한 JWT(assertion)를 만들어 Google OAuth 서버에 보내고
 *     1시간짜리 액세스 토큰을 받는다(accessToken(), 만료 1분 전까지 재사용 = 캐시).
 *  ③ POST https://fcm.googleapis.com/v1/projects/{프로젝트}/messages:send
 *     헤더 Authorization: Bearer {액세스 토큰}, 본문 {"message":{"token":기기토큰,"notification":{…},"data":{…}}}
 *  ④ 404·UNREGISTERED 응답 = 앱이 지워졌거나 토큰이 바뀐 기기 → DB 에서 그 토큰을 지운다.
 *  - @Component: @Service 와 같은 '빈 등록' 표시(업무 로직보다 기술적인 도구일 때 주로 쓴다).
 *  - DisposableBean.destroy(): 서버가 꺼질 때 스프링이 불러 준다 → 백그라운드 작업 스레드를 정리.
 *  - 가상 스레드(Java 21): Executors.newVirtualThreadPerTaskExecutor() 는 작업마다 가벼운 스레드를 만들어
 *    네트워크 대기가 많은 푸시 전송을 요청 처리와 분리해 비동기로 보낸다.
 *  - synchronized: 여러 스레드가 동시에 토큰을 새로 받으려 하지 않게 한 번에 하나만 들어오게 막는다.
 */
@Component
public class FcmPushSender implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final boolean enabled;
    private final String serviceAccountFile;
    private final DeviceTokenRepository tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private ServiceAccount account;
    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public FcmPushSender(@Value("${sbada.push.fcm.enabled:false}") boolean enabled,
                         @Value("${sbada.push.fcm.service-account-file:}") String serviceAccountFile,
                         DeviceTokenRepository tokens) {
        this.enabled = enabled;
        this.serviceAccountFile = serviceAccountFile;
        this.tokens = tokens;
        if (enabled) {
            log.info("FCM 푸시 사용: 서비스 계정 파일 {}", serviceAccountFile.isBlank() ? "(미설정)" : "설정됨");
        }
    }

    /** 설정에서 켰고(enabled=true) 서비스 계정 파일 경로도 있을 때만 실제로 보낸다 */
    public boolean isEnabled() {
        return enabled && !serviceAccountFile.isBlank();
    }

    /** 비동기로 보낸다(요청 처리 시간을 늘리지 않기 위해). */
    public void sendAsync(long userId, String title, String body, Map<String, String> data) {
        if (!isEnabled()) {
            return;
        }
        List<String> targets = tokens.tokensOf(userId);
        if (targets.isEmpty()) {
            return;
        }
        // submit: 람다를 백그라운드 스레드에 맡기고 바로 돌아온다(API 응답이 푸시 전송을 기다리지 않음)
        executor.submit(() -> {
            for (String token : targets) { // 한 사람의 모든 기기로 보낸다
                try {
                    sendOne(token, title, body, data);
                } catch (Exception e) {
                    log.warn("FCM 전송 실패: {}", e.getMessage());
                }
            }
        });
    }

    /** 기기 하나로 FCM HTTP v1 요청 1번 */
    private void sendOne(String token, String title, String body, Map<String, String> data) throws Exception {
        ServiceAccount sa = account();
        // notification = 폰 알림 표시줄에 뜨는 제목·내용, data = 앱이 눌렸을 때 읽는 추가 값(type, refType, refId)
        // channel_id 는 MainActivity.kt 가 만든 Android 알림 채널(sbada_default)과 같아야 한다
        Map<String, Object> message = Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", title, "body", body == null ? "" : body),
                "data", data,
                "android", Map.of("priority", "HIGH",
                        "notification", Map.of("channel_id", "sbada_default"))));
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://fcm.googleapis.com/v1/projects/" + sa.projectId() + "/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(message), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404 || (res.statusCode() == 400 && res.body().contains("UNREGISTERED"))) {
            tokens.deleteByToken(token);
        } else if (res.statusCode() / 100 != 2) {
            log.warn("FCM HTTP {}", res.statusCode());
        }
    }

    /** Google OAuth 2.0 '서비스 계정 JWT 교환' 방식으로 FCM 호출용 액세스 토큰을 얻는다(만료 1분 전까지 캐시) */
    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minusSeconds(60))) {
            return accessToken;
        }
        ServiceAccount sa = account();
        Instant now = Instant.now();
        // 서비스 계정 키(RS256)로 서명한 JWT를 OAuth 토큰과 바꾼다
        String assertion = Jwts.builder()
                .issuer(sa.clientEmail())
                .audience().add(sa.tokenUri()).and()
                .claim("scope", SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(sa.privateKey(), Jwts.SIG.RS256)
                .compact();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(sa.tokenUri()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("OAuth 토큰 발급 실패 HTTP " + res.statusCode());
        }
        Map<String, Object> body = Json.readMap(res.body());
        accessToken = String.valueOf(body.get("access_token"));
        long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 3600;
        accessTokenExpiresAt = now.plusSeconds(expiresIn);
        return accessToken;
    }

    /**
     * 서비스 계정 JSON 을 한 번만 읽어 기억한다(지연 로딩).
     * private_key 는 PEM 형식 문자열 → 머리·꼬리 줄과 공백을 지우고 Base64 디코딩 → PKCS#8 RSA 비밀 키 객체로 변환.
     */
    private synchronized ServiceAccount account() throws Exception {
        if (account == null) {
            Map<String, Object> json = Json.readMap(Files.readString(Path.of(serviceAccountFile)));
            String pem = String.valueOf(json.get("private_key"))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            Object tokenUri = json.get("token_uri");
            account = new ServiceAccount(String.valueOf(json.get("project_id")),
                    String.valueOf(json.get("client_email")),
                    tokenUri == null ? "https://oauth2.googleapis.com/token" : tokenUri.toString(), key);
        }
        return account;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }

    private record ServiceAccount(String projectId, String clientEmail, String tokenUri, PrivateKey privateKey) {
    }
}
