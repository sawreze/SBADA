package com.sbada.geo;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.ApiException;

/**
 * GPS 로 내 동네 찾기.
 *
 * | 메서드 | 경로 | 공개 | 설명 |
 * | GET | /api/geo/reverse?lat=37.556&lng=126.901 | 공개 | 좌표 → 스바다 동네 이름 |
 *
 * ■ 좌표로 동네 찾기 GET /api/geo/reverse (공개 — 처음 설치한 뒤 로그인 전 온보딩에서도 쓴다)
 *   응답 200 {"region":"서울 마포구 망원동","dong":"망원동","lat":37.556,"lng":126.901}
 *   처리 순서: 좌표 범위 확인 → 기억해 둔 결과(약 100m 단위)가 있으면 그대로 → 없으면 Nominatim → RegionNames 로 이름 만들기
 *   오류: 400 GEO_OUT_OF_RANGE(한국 밖 좌표) · 404 GEO_NOT_FOUND(동네를 정할 수 없는 곳: 바다 등) · 502 GEO_UNAVAILABLE(주소 서버 연결 실패)
 *   좌표는 저장하지 않는다(동네 이름만 앱이 PUT /api/me/region 으로 저장).
 *
 * [프로그래밍 설명]
 *  - ConcurrentHashMap: 여러 요청이 동시에 읽고 써도 안전한 Map. 같은 자리를 다시 물으면 바깥 서버를 부르지 않는다.
 */
@RestController
public class GeoController {

    private static final Logger log = LoggerFactory.getLogger(GeoController.class);
    private static final int CACHE_MAX = 5000;

    private final ReverseGeocoder geocoder;
    private final Map<String, RegionNames.Region> cache = new ConcurrentHashMap<>();

    public GeoController(ReverseGeocoder geocoder) {
        this.geocoder = geocoder;
    }

    @GetMapping("/api/geo/reverse")
    public Map<String, Object> reverse(@RequestParam double lat, @RequestParam double lng) {
        // 대한민국(제주 · 울릉 · 독도 포함) 대략의 범위
        if (lat < 33.0 || lat > 38.7 || lng < 124.5 || lng > 132.0) {
            throw ApiException.badRequest("GEO_OUT_OF_RANGE", "한국 안의 위치에서만 동네를 찾을 수 있어요.");
        }
        String key = String.format(Locale.ROOT, "%.3f,%.3f", lat, lng); // 소수 셋째 자리 ≈ 100m
        RegionNames.Region region = cache.get(key);
        if (region == null) {
            // 길 위(특히 휴대폰의 '대략적인 위치'로 1~2km 흔들린 좌표)는 길 이름 · 구만 오고 동이 없을 때가 있다
            // → 한 단계 넓게(동네 크기, zoom 14) 다시 묻는다
            Map<String, Object> address = geocoder.address(lat, lng, 16);
            region = RegionNames.from(address);
            if (region == null) {
                address = geocoder.address(lat, lng, 14);
                region = RegionNames.from(address);
            }
            if (region == null) {
                // 원인 확인용: 좌표는 소수 둘째 자리(약 1km)까지만, 주소는 어떤 칸이 왔는지만 남긴다
                log.info("동네를 정하지 못함 lat={} lng={} address-keys={}", String.format(Locale.ROOT, "%.2f", lat),
                        String.format(Locale.ROOT, "%.2f", lng), address.keySet());
                throw ApiException.notFound("GEO_NOT_FOUND", "이 위치의 동네를 찾지 못했어요. 직접 입력해 주세요.");
            }
            if (cache.size() < CACHE_MAX) {
                cache.put(key, region);
            }
        }
        return Map.of("region", region.region(), "dong", region.dong(), "lat", lat, "lng", lng);
    }
}
