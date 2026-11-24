package com.sbada.payment.events;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.payment.common.SecretFiles;

/** 서비스끼리 쓰는 내부 비밀 토큰(세 서버가 같은 파일을 공유, gitignore 된 data/). 비교는 걸린 시간으로 추측 못 하게 */
@Component
public class InternalSecret {

    /** gRPC 메타데이터 이름(소문자) */
    public static final String GRPC_KEY = "x-internal-token";

    private final String token;

    public InternalSecret(@Value("${sbada.internal.token-file}") String tokenFile) {
        this.token = Base64.getEncoder().encodeToString(SecretFiles.loadOrCreate(Path.of(tokenFile), 32));
    }

    public String value() {
        return token;
    }

    public boolean matches(String given) {
        return given != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                given.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
