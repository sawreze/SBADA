package com.sbada.geo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;
import com.sbada.common.Json;

/**
 * OpenStreetMap Nominatim 역지오코딩(좌표 → 주소). 키 · 요금 없음 — 지도(OSM 타일)와 같은 곳.
 * 이용 조건(https://operations.osmfoundation.org/policies/nominatim/)을 지킨다:
 *  - 앱 이름이 들어간 User-Agent 를 보낸다
 *  - 1초에 1번 넘게 부르지 않는다(여기서 간격을 둔다) · 같은 곳은 GeoController 가 기억해 두고 다시 묻지 않는다
 *  - 개발 · 시연용 소량 사용. 사용자가 많아지면 유료/자체 서버로 바꾼다(sbada.geo.nominatim-url)
 */
@Component
public class NominatimGeocoder implements ReverseGeocoder {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);

    private final String baseUrl;
    private final String userAgent;
    private final HttpClient http;
    private long lastCallMillis;

    public NominatimGeocoder(@Value("${sbada.geo.nominatim-url:https://nominatim.openstreetmap.org}") String baseUrl,
                             @Value("${sbada.geo.user-agent:sbada-app/1.0 (class project)}") String userAgent) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public Map<String, Object> address(double lat, double lng, int zoom) {
        URI uri = URI.create(String.format(Locale.ROOT,
                "%s/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=%d&addressdetails=1&accept-language=ko",
                baseUrl, lat, lng, zoom));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            waitTurn();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("주소 찾기(Nominatim) 실패: {}", e.getMessage());
            throw new ApiException(502, "GEO_UNAVAILABLE", "주소 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("주소 찾기(Nominatim) 응답 HTTP {}", response.statusCode());
            throw new ApiException(502, "GEO_UNAVAILABLE", "주소 서버 응답이 올바르지 않아요(HTTP " + response.statusCode() + ").");
        }
        Map<String, Object> body = Json.readMap(response.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> address = body.get("address") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        return address;
    }

    /** 이용 조건: 1초에 1번까지 */
    private synchronized void waitTurn() throws InterruptedException {
        long wait = lastCallMillis + 1100 - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCallMillis = System.currentTimeMillis();
    }
}
