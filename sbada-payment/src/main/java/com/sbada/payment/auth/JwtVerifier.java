package com.sbada.payment.auth;

import java.nio.file.Path;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.payment.common.ApiException;
import com.sbada.payment.common.SecretFiles;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 메인 서버(회원 서비스)가 발급한 로그인 토큰을 검증만 한다.
 * 같은 키 파일을 읽으므로 따로 로그인할 필요가 없다(서비스마다 회원 DB 를 복사하지 않는다).
 */
@Component
public class JwtVerifier {

    private final SecretKey key;
    private final String issuer;

    public JwtVerifier(@Value("${sbada.jwt.secret-file}") String secretFile, @Value("${sbada.jwt.issuer}") String issuer) {
        this.key = Keys.hmacShaKeyFor(SecretFiles.loadOrCreate(Path.of(secretFile), 32));
        this.issuer = issuer;
    }

    public long verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireIssuer(issuer).build()
                    .parseSignedClaims(token).getPayload();
            return Long.parseLong(claims.getSubject());
        } catch (ExpiredJwtException e) {
            throw new ApiException(401, "TOKEN_EXPIRED", "로그인이 만료되었습니다. 다시 로그인해 주세요.");
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(401, "TOKEN_INVALID", "로그인 정보가 올바르지 않습니다.");
        }
    }
}
