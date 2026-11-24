package com.sbada.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;

/**
 * 서비스끼리 쓰는 내부 비밀 토큰. 파일(./data/.internal-token, gitignore)을 결제 서비스와 공유한다.
 * 비교는 MessageDigest.isEqual(걸린 시간으로 값을 추측하는 공격 방지).
 */
@Component
public class InternalToken {

    public static final String HEADER = "X-Internal-Token";
    /** gRPC 메타데이터 이름(소문자) — 배달부 호출마다 붙인다 (2026-11-25 MSA 2단계) */
    public static final String GRPC_KEY = "x-internal-token";

    private final byte[] expected;

    public InternalToken(@Value("${sbada.internal.token-file:./data/.internal-token}") String file) {
        this.expected = Base64.getEncoder().encodeToString(loadOrCreate(Path.of(file)))
                .getBytes(StandardCharsets.US_ASCII);
    }

    public String value() {
        return new String(expected, StandardCharsets.US_ASCII);
    }

    /** gRPC 서버 인터셉터용: 맞으면 true */
    public boolean matches(String given) {
        return given != null && MessageDigest.isEqual(expected, given.trim().getBytes(StandardCharsets.US_ASCII));
    }

    public void check(String given) {
        if (given == null || !MessageDigest.isEqual(expected, given.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(401, "INTERNAL_AUTH_FAILED", "내부 서비스 인증에 실패했습니다.");
        }
    }

    private static byte[] loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] decoded = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
                if (decoded.length >= 32) {
                    return decoded;
                }
            }
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(secret), StandardCharsets.US_ASCII);
            return secret;
        } catch (IOException e) {
            throw new IllegalStateException("내부 토큰 파일을 준비하지 못했습니다: " + file, e);
        }
    }
}
