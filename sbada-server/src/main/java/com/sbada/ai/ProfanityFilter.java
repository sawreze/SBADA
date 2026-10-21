package com.sbada.ai;

import java.util.List;
import java.util.Locale;

/**
 * 1차 콘텐츠 검사: 제목 · 설명에 흔한 욕설 · 비속어 · 성적인 단어가 있는지 서버 안에서 바로 본다.
 * AI(Gemma)가 꺼져 있어도 동작하고, 띄어쓰기 · 특수문자 · 숫자를 끼워 넣은 변형("씨 발", "시1발")도 잡는다.
 * 목록은 예시 수준이다. 문맥이 필요한 판단(혐오 표현 · 은어)은 2차로 Gemma 가 한다(ModerationService).
 */
public final class ProfanityFilter {

    /** 정규화(공백 · 특수문자 · 숫자 제거, 소문자) 뒤의 형태로 적는다 */
    private static final List<String> WORDS = List.of(
            "씨발", "시발", "씨바", "씨팔", "ㅅㅂ", "ㅆㅂ", "ㅅㅃ", "병신", "븅신", "ㅂㅅ", "개새끼", "개새기", "개색기", "개색히",
            "새끼", "좆", "존나", "졸라", "ㅈㄴ", "니미", "느금", "닥쳐", "꺼져", "미친놈", "미친년", "지랄", "ㅈㄹ", "염병",
            "엠창", "애미", "애비", "창녀", "걸레", "보지", "자지", "섹스", "야동", "변태",
            "fuck", "shit", "bitch", "asshole", "porn", "sex");

    private ProfanityFilter() {
    }

    /** 걸린 단어(없으면 null) */
    public static String find(String... texts) {
        for (String t : texts) {
            if (t == null) {
                continue;
            }
            String n = normalize(t);
            for (String w : WORDS) {
                if (n.contains(w)) {
                    return w;
                }
            }
        }
        return null;
    }

    static String normalize(String s) {
        // 한글 · 자음 · 영문만 남긴다("씨 발", "씨-발", "시1발" → "씨발", "시발")
        return s.toLowerCase(Locale.ROOT).replaceAll("[^가-힣ㄱ-ㅎa-z]", "");
    }
}
