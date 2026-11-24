package com.sbada.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sbada.common.ApiException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * HS256 JWT 발급·검증(7장 7절).
 * 서명 키는 소스에 적지 않고, 처음 실행 때 32바이트 난수로 만들어 data/.jwt-secret 에 보관한다.
 * payload(sub·iss·iat·exp)는 누구나 읽을 수 있으므로 비밀을 넣지 않는다.
 *
 * [프로그래밍 설명 — JWT(JSON Web Token)]
 *  - 모양: 헤더.페이로드.서명   (각 부분은 Base64URL 로 인코딩한 문자열, 점(.)으로 구분)
 *      헤더    {"alg":"HS256"}
 *      페이로드 {"iss":"sbada","sub":"1","iat":1792500000,"exp":1792507200}
 *              iss=발급자, sub=주인(사용자 번호), iat=발급 시각, exp=만료 시각(초)
 *      서명    HMAC-SHA256(헤더.페이로드, 서버만 아는 비밀 키)
 *  - 페이로드는 암호화가 아니라 인코딩이라 누구나 읽을 수 있다. 대신 내용을 바꾸면 서명이 맞지 않아 위조를 알아챈다.
 *  - 서버는 세션을 저장하지 않고(STATELESS) 매 요청의 토큰 서명만 검사하면 된다 → 서버를 여러 대로 늘리기도 쉽다.
 *  - 라이브러리: jjwt 0.13 (Jwts.builder / Jwts.parser).
 *  - @Value("${…}"): application.properties 의 값을 생성자 파라미터로 주입받는다.
 *      sbada.jwt.secret-file=./data/.jwt-secret, sbada.jwt.issuer=sbada, sbada.jwt.ttl-seconds=7200
 */
@Service
public class JwtService {

    private final SecretKey key;    // HMAC 서명·검증에 쓰는 비밀 키(32바이트 = 256비트)
    private final String issuer;
    private final long ttlSeconds;  // 토큰 유효 시간(초)

    public JwtService(@Value("${sbada.jwt.secret-file}") String secretFile,
                      @Value("${sbada.jwt.issuer}") String issuer,
                      @Value("${sbada.jwt.ttl-seconds}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(loadOrCreateSecret(Path.of(secretFile)));
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** 로그인 성공 시 부른다: 기본 유효 시간(2시간)짜리 토큰 */
    public String issue(long userId) {
        return issue(userId, Duration.ofSeconds(ttlSeconds));
    }

    /** 테스트에서 만료 토큰을 만들 때도 쓴다(음수 기간 = 이미 만료). */
    public String issue(long userId, Duration ttl) {
        Instant now = Instant.now();
        // 빌더 패턴: 값을 하나씩 설정하고 마지막 compact() 에서 "xxx.yyy.zzz" 문자열을 만든다
        return Jwts.builder()
                .issuer(issuer)
                .subject(Long.toString(userId))       // sub: 사용자 번호(문자열로 넣는 것이 표준)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)                        // 키 길이를 보고 HS256 서명 알고리즘을 고른다
                .compact();
    }

    /** 서명·발급자·만료를 검사하고 사용자 번호를 돌려준다. 실패하면 401. */
    public long verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)          // 같은 키로 서명을 다시 계산해 비교(다르면 위조)
                    .requireIssuer(issuer)    // iss 가 "sbada" 가 아니면 거절
                    .build()
                    .parseSignedClaims(token) // 여기서 exp 도 자동 검사(지났으면 ExpiredJwtException)
                    .getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (ExpiredJwtException e) {
            // 만료는 따로 구분 → 앱이 "다시 로그인해 주세요" 화면으로 보낼 수 있다
            throw new ApiException(401, "TOKEN_EXPIRED", "로그인이 만료되었습니다. 다시 로그인해 주세요.");
        } catch (JwtException | IllegalArgumentException e) { // 멀티 catch: 둘 중 어느 예외든 같은 처리
            throw new ApiException(401, "TOKEN_INVALID", "로그인 정보가 올바르지 않습니다.");
        }
    }

    /**
     * 비밀 키 파일을 읽고, 없거나 짧으면 새로 만든다.
     * 키를 소스 코드나 Git 에 넣지 않기 위해서다(키가 새면 누구나 토큰을 위조할 수 있다).
     * 서버를 다시 켜도 같은 키를 쓰므로, 이미 발급한 토큰이 계속 유효하다.
     */
    private static byte[] loadOrCreateSecret(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] decoded = Base64.getDecoder()
                        .decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
                if (decoded.length >= 32) { // HS256 은 256비트(32바이트) 이상 키가 필요하다
                    return decoded;
                }
            }
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret); // 암호학적으로 안전한 난수(Random 과 달리 예측이 어렵다)
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(secret), StandardCharsets.US_ASCII);
            return secret;
        } catch (IOException e) {
            // 키가 없으면 서버가 동작할 수 없으므로 시작을 멈춘다(체크 예외 → 런타임 예외로 감싸 던짐)
            throw new IllegalStateException("JWT 키 파일을 준비하지 못했습니다: " + file, e);
        }
    }
}
