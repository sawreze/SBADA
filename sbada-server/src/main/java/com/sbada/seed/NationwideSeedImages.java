package com.sbada.seed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 전국 상품(V9) 500개의 대표 사진 classpath:seed/q001.jpg ~ q500.jpg 를 업로드 폴더(seed-qNNN.jpg)로 복사한다.
 * DB 의 사진 행은 V10__nationwide_photos.sql 이 넣는다. 파일이 없는 번호는 건너뛴다(사진 없이 보임).
 *
 * [프로그래밍 설명]
 *  - ApplicationRunner: 서버가 다 켜진 직후 run() 이 한 번 실행된다(SeedImageInstaller 와 같은 방식).
 *  - ClassPathResource: jar/빌드 결과 안의 resources 파일을 읽는다. 이미 복사된 파일은 덮어쓰지 않는다.
 */
@Component
public class NationwideSeedImages implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NationwideSeedImages.class);
    static final int COUNT = 500;

    private final Path uploadDir;
    private final boolean enabled;

    public NationwideSeedImages(@Value("${sbada.upload.dir}") String uploadDir,
                                @Value("${sbada.seed.enabled:true}") boolean enabled) {
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        int copied = 0;
        try {
            Files.createDirectories(uploadDir);
            for (int i = 1; i <= COUNT; i++) {
                String name = String.format("q%03d.jpg", i);
                Path target = uploadDir.resolve("seed-" + name);
                ClassPathResource res = new ClassPathResource("seed/" + name);
                if (Files.exists(target) || !res.exists()) {
                    continue;
                }
                try (InputStream in = res.getInputStream()) {
                    Files.copy(in, target);
                    copied++;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("전국 상품 사진을 업로드 폴더에 복사하지 못했습니다: " + uploadDir, e);
        }
        if (copied > 0) {
            log.info("전국 상품 사진 {}장을 {} 에 복사했습니다.", copied, uploadDir);
        }
    }
}
