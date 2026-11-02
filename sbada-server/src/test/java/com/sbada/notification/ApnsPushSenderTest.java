package com.sbada.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;

/** APNs 직접 푸시: .p8 키 읽기 · ES256 provider token · 알림 본문(aps) 모양 */
class ApnsPushSenderTest {

    @TempDir
    Path dir;

    @Test
    void providerTokenIsEs256SignedWithKeyIdAndTeamId() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1")); // Apple .p8 과 같은 P-256 곡선
        KeyPair pair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path p8 = dir.resolve("AuthKey_TEST.p8");
        Files.writeString(p8, pem);

        ApnsPushSender sender = new ApnsPushSender(true, p8.toString(), "ABC123DEFG", "TEAM123456",
                "com.sbada.sbada", true, null);
        assertThat(sender.isEnabled()).isTrue();

        String jwt = sender.providerToken();
        Jws<Claims> parsed = Jwts.parser().verifyWith(pair.getPublic()).build().parseSignedClaims(jwt);
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        assertThat(parsed.getHeader().getKeyId()).isEqualTo("ABC123DEFG");
        assertThat(parsed.getPayload().getIssuer()).isEqualTo("TEAM123456");
        assertThat(parsed.getPayload().getIssuedAt()).isNotNull();
        // 50분 안에는 같은 토큰을 다시 쓴다(Apple: 너무 자주 바꾸면 TooManyProviderTokenUpdates)
        assertThat(sender.providerToken()).isEqualTo(jwt);
    }

    @Test
    void disabledWithoutKeySettings() {
        assertThat(new ApnsPushSender(true, "", "", "", "com.sbada.sbada", true, null).isEnabled()).isFalse();
        assertThat(new ApnsPushSender(false, "/x.p8", "K", "T", "com.sbada.sbada", true, null).isEnabled()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadHasApsAlertAndAppData() {
        Map<String, Object> body = ApnsPushSender.payload("교환 요청", "민지님이 교환을 원해요",
                Map.of("type", "EXCHANGE_REQUESTED", "refType", "EXCHANGE", "refId", "7"));
        Map<String, Object> aps = (Map<String, Object>) body.get("aps");
        assertThat((Map<String, Object>) aps.get("alert"))
                .containsEntry("title", "교환 요청").containsEntry("body", "민지님이 교환을 원해요");
        assertThat(aps).containsEntry("sound", "default").containsEntry("thread-id", "EXCHANGE_REQUESTED");
        assertThat(body).containsEntry("refType", "EXCHANGE").containsEntry("refId", "7");
    }
}
