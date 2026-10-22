package com.sbada.geo;

import java.util.Map;

/**
 * 주소 조각(OpenStreetMap Nominatim 응답의 address)을 스바다 동네 이름으로 바꾼다.
 * 스바다 동네 이름 = app_users.region 과 같은 모양: "서울 마포구 망원동", "경기 수원시 인계동", "세종 보람동"
 *  - 시도: 특별시 · 광역시 · 도를 짧게(서울특별시 → 서울, 경기도 → 경기, 강원특별자치도 → 강원)
 *  - 시군구: 특별시 · 광역시는 구 · 군(borough, county), 도는 시 · 군(city, county) — 도의 '구'(팔달구)는 넣지 않는다(기존 시연 동네와 같은 모양)
 *  - 동: 법정동(quarter, 인계동) → 없으면 행정동(suburb, 망원1동 → 숫자를 빼서 망원동) → 읍 · 면 · 리(town, village)
 *
 * [프로그래밍 설명] 네트워크와 상관없는 순수 함수라 테스트에서 바로 검사한다(RegionNamesTest).
 */
public final class RegionNames {

    private RegionNames() {
    }

    private static final Map<String, String> SIDO = Map.ofEntries(
            Map.entry("서울특별시", "서울"), Map.entry("부산광역시", "부산"), Map.entry("대구광역시", "대구"),
            Map.entry("인천광역시", "인천"), Map.entry("광주광역시", "광주"), Map.entry("대전광역시", "대전"),
            Map.entry("울산광역시", "울산"), Map.entry("세종특별자치시", "세종"), Map.entry("경기도", "경기"),
            Map.entry("강원특별자치도", "강원"), Map.entry("강원도", "강원"), Map.entry("충청북도", "충북"),
            Map.entry("충청남도", "충남"), Map.entry("전북특별자치도", "전북"), Map.entry("전라북도", "전북"),
            Map.entry("전라남도", "전남"), Map.entry("경상북도", "경북"), Map.entry("경상남도", "경남"),
            Map.entry("제주특별자치도", "제주"));

    /** 찾은 동네. dong = 화면에 보이는 짧은 이름(망원동) */
    public record Region(String region, String dong) {
    }

    /** 한국 주소가 아니거나 동네를 정할 수 없으면 null */
    public static Region from(Map<String, Object> address) {
        if (address == null || !"kr".equals(str(address, "country_code"))) {
            return null;
        }
        String province = str(address, "province");
        String city = str(address, "city");
        String sido;
        String sigungu;
        if (province != null) { // 도: 경기도 수원시
            sido = SIDO.getOrDefault(province, province);
            sigungu = firstNonNull(city, str(address, "county"));
        } else { // 특별시 · 광역시 · 세종: 서울특별시 마포구
            if (city == null) {
                return null;
            }
            sido = SIDO.getOrDefault(city, city);
            sigungu = firstNonNull(str(address, "borough"), str(address, "county"));
        }
        String dong = dongName(address);
        if (dong == null) {
            return null;
        }
        StringBuilder region = new StringBuilder(sido);
        if (sigungu != null && !sigungu.equals(sido)) {
            region.append(' ').append(sigungu);
        }
        region.append(' ').append(dong);
        return new Region(region.toString(), dong);
    }

    static String dongName(Map<String, Object> address) {
        String quarter = str(address, "quarter");
        String suburb = str(address, "suburb");
        String admin = suburb != null && suburb.endsWith("동") ? withoutNumber(suburb) : null;
        if (quarter != null && quarter.endsWith("동")) {
            String legal = withoutNumber(quarter);
            // 법정동(quarter)과 행정동(suburb)이 서로 다른 동이면 행정동을 쓴다.
            // 강 위 · 구 경계에서는 법정동 경계가 옆 구까지 넘어와 '영등포구 망원동'처럼 구와 동이 어긋난 답이 온다
            // (2026-10-22 실제: 서부간선로 37.5496,126.8897 → quarter 망원동(마포구) · suburb 양평2동 · borough 영등포구).
            // 행정동은 구(borough)와 같은 행정 구역 체계라 항상 짝이 맞는다
            return admin != null && !admin.equals(legal) ? admin : legal;
        }
        if (admin != null) {
            return admin;
        }
        for (String key : new String[] {"town", "village", "quarter", "suburb"}) {
            String v = str(address, key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 행정동 번호를 뺀다: 망원1동 → 망원동, 중1동 → 중동, 창제1동 → 창제동(이미 법정동이면 그대로) */
    static String withoutNumber(String dong) {
        return dong.replaceAll("^(.+?)제?\\d+동$", "$1동");
    }

    private static String str(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
