package com.sbada.geo;

import java.util.Map;

/**
 * 좌표 → 주소 조각(OpenStreetMap Nominatim 의 address 모양: province, city, borough, quarter, suburb …).
 * 실제 구현은 NominatimGeocoder, 테스트는 가짜(@Primary)로 바꿔 끼운다(카카오 로그인과 같은 방식).
 */
public interface ReverseGeocoder {

    /**
     * zoom = 찾는 범위(16 = 길 · 건물 근처, 14 = 동네 크기). 실패하면 ApiException(502 GEO_UNAVAILABLE)
     */
    Map<String, Object> address(double lat, double lng, int zoom);
}
