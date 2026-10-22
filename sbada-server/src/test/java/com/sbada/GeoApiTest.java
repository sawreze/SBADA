package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import com.sbada.geo.RegionNames;
import com.sbada.geo.ReverseGeocoder;

/**
 * GPS 동네 찾기: 실제 Nominatim 대신 가짜 주소 서버(@Primary)로 검사한다.
 * 주소 조각은 2026-10-22 실제 Nominatim 응답에서 옮겨 왔다(망원 · 해운대 · 수원 · 세종 · 제주).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-geo-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class GeoApiTest {

    static final Map<String, Object> MANGWON = Map.of("road", "희우정로20길", "neighbourhood", "9통",
            "suburb", "망원1동", "borough", "마포구", "city", "서울특별시", "country_code", "kr");
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class FakeGeo {
        @Bean
        @Primary
        ReverseGeocoder fakeReverseGeocoder() {
            return (lat, lng, zoom) -> {
                CALLS.incrementAndGet();
                if (lat > 37.0) {
                    return MANGWON;
                }
                // 36.6~37 = 길 위: zoom 16 은 길 이름 · 구만, zoom 14(동네 크기)는 동까지. 그 아래(바다)는 동네를 정할 수 없음
                if (lat > 36.6) {
                    return zoom == 16
                            ? Map.of("road", "인계로", "borough", "팔달구", "city", "수원시", "province", "경기도", "country_code", "kr")
                            : Map.of("quarter", "인계동", "city", "수원시", "province", "경기도", "country_code", "kr");
                }
                return Map.of("country_code", "kr");
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void reverseIsPublicAndCached() throws Exception {
        int before = CALLS.get();
        for (int i = 0; i < 2; i++) { // 로그인 없이(온보딩) · 같은 자리 두 번 → 주소 서버는 한 번만
            mvc.perform(get("/api/geo/reverse").param("lat", "37.5560").param("lng", "126.9010"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.region").value("서울 마포구 망원동"))
                    .andExpect(jsonPath("$.dong").value("망원동"));
        }
        assertThat(CALLS.get() - before).isEqualTo(1);

        // 길 위라 동이 없으면 한 단계 넓게(동네 크기) 다시 물어 동을 찾는다(휴대폰 '대략적인 위치'에서 실제로 있었던 경우)
        mvc.perform(get("/api/geo/reverse").param("lat", "36.7").param("lng", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.region").value("경기 수원시 인계동"));
        mvc.perform(get("/api/geo/reverse").param("lat", "36.5").param("lng", "129.9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GEO_NOT_FOUND"));
        mvc.perform(get("/api/geo/reverse").param("lat", "48.85").param("lng", "2.35")) // 파리
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GEO_OUT_OF_RANGE"));
    }

    @Test
    void regionNamesMatchSbadaFormat() {
        assertThat(RegionNames.from(MANGWON).region()).isEqualTo("서울 마포구 망원동");
        assertThat(RegionNames.from(Map.of("quarter", "중동", "suburb", "중1동", "borough", "해운대구",
                "city", "부산광역시", "country_code", "kr")).region()).isEqualTo("부산 해운대구 중동");
        assertThat(RegionNames.from(Map.of("quarter", "인계동", "city_district", "팔달구", "city", "수원시",
                "province", "경기도", "country_code", "kr")).region()).isEqualTo("경기 수원시 인계동");
        assertThat(RegionNames.from(Map.of("quarter", "보람동", "village", "신촌리", "city", "세종특별자치시",
                "country_code", "kr")).region()).isEqualTo("세종 보람동");
        assertThat(RegionNames.from(Map.of("quarter", "연동", "suburb", "신제주", "city", "제주시",
                "province", "제주특별자치도", "country_code", "kr")).region()).isEqualTo("제주 제주시 연동");
        assertThat(RegionNames.from(Map.of("city", "Paris", "country_code", "fr"))).isNull();
    }
}
