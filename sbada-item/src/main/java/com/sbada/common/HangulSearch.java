package com.sbada.common;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 물건 검색 규칙(GET /api/items?q=).
 *  - 검색어를 띄어쓰기로 나눠 '모든 단어'가 물건 이름 또는 등록자 동네(주소)에 들어 있으면 찾은 것으로 본다.
 *      "헤드폰" · "망원동" · "마포구 망원동" · "망원 헤드폰" 모두 된다.
 *  - 초성 검색: 단어 속 자음(ㄱ~ㅎ)은 그 자리 글자의 첫소리와 비교한다. "ㅅㅁㅌㅍ" → 스마트폰, "ㅁㅇ" → 망원동, "스ㅁ" → 스마트폰
 *  - 대소문자 · 띄어쓰기는 무시한다("qhd모니터" = "QHD 모니터").
 *  - 주소 앞에 붙는 시 이름(서울 · 서울시 · 서울특별시)은 모든 동네와 맞는 것으로 본다(시연 동네가 모두 서울이라서).
 *
 * [프로그래밍 설명]
 *  한글 음절(가~힣)은 유니코드에서 (초성 × 588) + (중성 × 28) + 종성 순서로 놓여 있다.
 *  그래서 (글자 - '가') / 588 이 초성 번호(0~18)가 된다.
 */
public final class HangulSearch {

    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'};
    private static final Set<String> CITY_WORDS = Set.of("서울", "서울시", "서울특별시");

    private HangulSearch() {
    }

    /** 검색어의 모든 단어가 [fields] 중 하나에 들어 있나 */
    public static boolean matches(String query, String... fields) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return true;
        }
        for (String token : tokens) {
            if (CITY_WORDS.contains(token)) {
                continue;
            }
            boolean hit = Arrays.stream(fields).anyMatch(f -> f != null && contains(normalize(f), token));
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    static List<String> tokens(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(query.trim().split("\\s+")).map(HangulSearch::normalize).filter(t -> !t.isEmpty()).toList();
    }

    static String normalize(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** target 안에 token 이 연속으로 들어 있나(token 의 자음은 초성과 비교) */
    static boolean contains(String target, String token) {
        int n = target.length(), m = token.length();
        for (int start = 0; start + m <= n; start++) {
            int k = 0;
            while (k < m && same(target.charAt(start + k), token.charAt(k))) {
                k++;
            }
            if (k == m) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(char t, char q) {
        if (t == q) {
            return true;
        }
        return isConsonant(q) && choseong(t) == q;
    }

    static boolean isConsonant(char c) {
        return c >= 'ㄱ' && c <= 'ㅎ';
    }

    /** 한글 음절의 초성. 한글이 아니면 0 */
    static char choseong(char c) {
        if (c < '가' || c > '힣') {
            return 0;
        }
        return CHOSEONG[(c - '가') / 588];
    }
}
