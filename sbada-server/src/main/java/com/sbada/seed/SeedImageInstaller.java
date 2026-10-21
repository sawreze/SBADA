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
 * 시연용 표본 자료의 사진 파일을 업로드 폴더에 놓는다.
 * 계정·물건·교환 요청 행은 Flyway(V2__seed_demo_data.sql, V4__replace_demo_items.sql)가 넣고,
 * 그 행이 가리키는 사진은 SQL 로 만들 수 없어서 여기서 복사한다.
 *   - seed-s01.jpg ~ seed-s16.jpg : V2 의 첫 표본 물건(V4 가 지우지만, 빈 DB 에서 V2 가 먼저 돌 때 필요)
 *   - seed-p01.jpg ~ seed-p50.jpg : V4 의 상품 50개(사용자가 보낸 사진)
 * 이미 있는 파일은 건드리지 않는다.
 *
 * [프로그래밍 설명]
 *  - ApplicationRunner: 스프링 부트가 모든 빈 준비를 마친 '직후' run() 을 한 번 실행해 준다(시작 작업용).
 *  - ClassPathResource("seed/s01.jpg"): src/main/resources/seed/ 안의 파일 → jar 안에 들어 있어도 읽을 수 있다.
 *  - try-with-resources: try ( … ) 안에서 연 스트림은 끝나면 자동으로 닫힌다(파일 핸들 누수 방지).
 *  - sbada.seed.enabled=false 로 끄면 아무것도 하지 않는다(실서비스용).
 */
@Component
public class SeedImageInstaller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedImageInstaller.class);
    /** 파일 이름 앞글자 → 개수: s01~s16(V2), p01~p50(V4) */
    static final java.util.Map<String, Integer> SEED_IMAGES = java.util.Map.of("s", 16, "p", 50);

    private final Path uploadDir;
    private final boolean enabled;

    public SeedImageInstaller(@Value("${sbada.upload.dir}") String uploadDir,
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
            for (var set : SEED_IMAGES.entrySet()) {
                for (int i = 1; i <= set.getValue(); i++) {
                    String name = String.format("%s%02d.jpg", set.getKey(), i);
                    Path target = uploadDir.resolve("seed-" + name);
                    if (Files.exists(target)) {
                        continue;
                    }
                    try (InputStream in = new ClassPathResource("seed/" + name).getInputStream()) {
                        Files.copy(in, target);
                        copied++;
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("표본 사진을 업로드 폴더에 복사하지 못했습니다: " + uploadDir, e);
        }
        if (copied > 0) {
            log.info("표본 사진 {}장을 {} 에 복사했습니다.", copied, uploadDir);
        }
    }
}
