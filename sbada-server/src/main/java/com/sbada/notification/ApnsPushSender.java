package com.sbada.notification;

import java.net.URI;
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
import java.util.LinkedHashMap;
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
 * Apple APNs 직접 푸시(아이폰). 서버 → APNs(Apple) → 아이폰 알림 센터. Firebase 를 거치지 않는다.
 *
 * 사용 조건(유료 Apple Developer 계정): Certificates, Identifiers & Profiles → Keys 에서 'Apple Push Notifications service'
 * 키를 만들어 .p8 파일을 받고, application-local.properties 에 적는다(Git·공개 레포에 절대 올리지 않음):
 *   sbada.push.apns.enabled=true
 *   sbada.push.apns.key-file=/Users/나/keys/AuthKey_XXXXXXXXXX.p8
 *   sbada.push.apns.key-id=XXXXXXXXXX      (키 ID 10자리)
 *   sbada.push.apns.team-id=YYYYYYYYYY     (팀 ID 10자리)
 *   sbada.push.apns.bundle-id=com.sbada.sbada
 *   sbada.push.apns.sandbox=true           (Xcode 로 설치한 개발 빌드 = sandbox, TestFlight·App Store = false)
 *
 * [프로그래밍 설명 — APNs 토큰 기반 인증(.p8) 호출 순서]
 *  ① .p8 파일(PKCS#8 PEM) → EC(P-256) 비밀 키 객체
 *  ② JWT 헤더 {alg:ES256, kid:키ID}, 본문 {iss:팀ID, iat:지금} 을 ES256 으로 서명 → 'provider token'.
 *     Apple 규칙: 20분~60분 사이에 새로 만들어야 하고(너무 자주 만들면 TooManyProviderTokenUpdates) → 50분 캐시.
 *  ③ HTTP/2 POST https://api(.sandbox).push.apple.com/3/device/{기기토큰}
 *     헤더 authorization: bearer {JWT}, apns-topic: 번들ID, apns-push-type: alert, apns-priority: 10
 *     본문 {"aps":{"alert":{"title","body"},"sound":"default","thread-id":종류}, "type":…, "refType":…, "refId":…}
 *  ④ 410 Unregistered / 400 BadDeviceToken = 앱이 지워졌거나 다른 환경의 토큰 → DB 에서 그 토큰을 지운다.
 *  - Java 11+ HttpClient 는 HTTP/2 를 지원한다(APNs 는 HTTP/2 만 받는다).
 */
@Component
public class ApnsPushSender implements DisposableBean {

    /** 앱(PushService)이 APNs 기기 토큰을 등록할 때 쓰는 platform 값 */
    public static final String PLATFORM = "ios-apns";

    private static final Logger log = LoggerFactory.getLogger(ApnsPushSender.class);

    private final boolean enabled;
    private final String keyFile;
    private final String keyId;
    private final String teamId;
    private final String bundleId;
    private final String host;
    private final DeviceTokenRepository tokens;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private PrivateKey key;
    private String providerToken;
    private Instant providerTokenAt = Instant.EPOCH;

    public ApnsPushSender(@Value("${sbada.push.apns.enabled:false}") boolean enabled,
                          @Value("${sbada.push.apns.key-file:}") String keyFile,
                          @Value("${sbada.push.apns.key-id:}") String keyId,
                          @Value("${sbada.push.apns.team-id:}") String teamId,
                          @Value("${sbada.push.apns.bundle-id:com.sbada.sbada}") String bundleId,
                          @Value("${sbada.push.apns.sandbox:true}") boolean sandbox,
                          DeviceTokenRepository tokens) {
        this.enabled = enabled;
        this.keyFile = keyFile.trim();
        this.keyId = keyId.trim();
        this.teamId = teamId.trim();
        this.bundleId = bundleId.trim();
        this.host = sandbox ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com";
        this.tokens = tokens;
        if (enabled) {
            log.info("APNs 푸시 사용: {} · 키 {}", sandbox ? "sandbox(개발)" : "production",
                    isEnabled() ? "설정됨" : "(키 파일 · key-id · team-id 중 미설정 있음)");
        }
    }

    /** 켰고, 키 파일 · 키 ID · 팀 ID 가 다 있을 때만 실제로 보낸다 */
    public boolean isEnabled() {
        return enabled && !keyFile.isBlank() && !keyId.isBlank() && !teamId.isBlank();
    }

    /** 비동기 전송(API 응답이 Apple 서버를 기다리지 않게) */
    public void sendAsync(long userId, String title, String body, Map<String, String> data) {
        if (!isEnabled()) {
            return;
        }
        List<String> targets = tokens.tokensOf(userId, PLATFORM);
        if (targets.isEmpty()) {
            return;
        }
        executor.submit(() -> {
            for (String token : targets) {
                try {
                    sendOne(token, title, body, data);
                } catch (Exception e) {
                    log.warn("APNs 전송 실패: {}", e.getMessage());
                }
            }
        });
    }

    /** APNs 로 보낼 JSON 본문(aps 사전 + 앱이 알림을 눌렀을 때 읽을 값). 테스트에서도 쓴다 */
    static Map<String, Object> payload(String title, String body, Map<String, String> data) {
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", Map.of("title", title, "body", body == null ? "" : body));
        aps.put("sound", "default");
        // 같은 종류(채팅 · 교환 …)끼리 알림 센터에서 묶여 보인다
        aps.put("thread-id", data.getOrDefault("type", "sbada"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", aps);
        root.putAll(data);
        return root;
    }

    private void sendOne(String token, String title, String body, Map<String, String> data) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(host + "/3/device/" + token))
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "bearer " + providerToken())
                .header("apns-topic", bundleId)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload(title, body, data)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int code = res.statusCode();
        if (code == 410 || (code == 400 && res.body().contains("BadDeviceToken"))) {
            tokens.deleteByToken(token);
        } else if (code == 403 && res.body().contains("ExpiredProviderToken")) {
            synchronized (this) {
                providerToken = null; // 다음 전송 때 새로 만든다
            }
        } else if (code / 100 != 2) {
            log.warn("APNs HTTP {} {}", code, res.body());
        }
    }

    /** ES256 provider token(50분 캐시) */
    synchronized String providerToken() throws Exception {
        if (providerToken != null && Instant.now().isBefore(providerTokenAt.plus(Duration.ofMinutes(50)))) {
            return providerToken;
        }
        Instant now = Instant.now();
        providerToken = Jwts.builder()
                .header().keyId(keyId).and()
                .issuer(teamId)
                .issuedAt(Date.from(now))
                .signWith(key(), Jwts.SIG.ES256)
                .compact();
        providerTokenAt = now;
        return providerToken;
    }

    private synchronized PrivateKey key() throws Exception {
        if (key == null) {
            key = readP8(Files.readString(Path.of(keyFile)));
        }
        return key;
    }

    /** .p8(PEM) 문자열 → EC 비밀 키 */
    static PrivateKey readP8(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
