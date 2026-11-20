package com.sbada.payment.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 비밀값 파일 읽기/만들기. 메인 서버와 같은 규칙(Base64 한 줄, 32바이트 이상)이라 두 서비스가 같은 파일을 공유할 수 있다.
 * 파일은 gitignore 된 data/ 폴더에 두므로 깃에 올라가지 않는다.
 */
public final class SecretFiles {

    private SecretFiles() {
    }

    public static byte[] loadOrCreate(Path file, int bytes) {
        try {
            if (Files.exists(file)) {
                byte[] decoded = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
                if (decoded.length >= bytes) {
                    return decoded;
                }
            }
            byte[] secret = new byte[bytes];
            new SecureRandom().nextBytes(secret);
            write(file, Base64.getEncoder().encodeToString(secret).getBytes(StandardCharsets.US_ASCII));
            return secret;
        } catch (IOException e) {
            throw new IllegalStateException("비밀값 파일을 준비하지 못했습니다: " + file, e);
        }
    }

    /** 새 파일을 쓰고, 가능하면 주인만 읽을 수 있게(600) 권한을 좁힌다 */
    public static void write(Path file, byte[] content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, content);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 등 POSIX 권한이 없는 파일 시스템: 건너뜀
        }
    }
}
