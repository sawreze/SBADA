package com.sbada.ai;

import java.util.StringJoiner;

import com.sbada.common.Category;
import com.sbada.common.ItemCondition;

/**
 * Gemma 에 보낼 지시문. 출력은 JSON 하나로 강제해 서버가 그대로 해석할 수 있게 한다.
 *
 * [프로그래밍 설명]
 *  - 프롬프트 엔지니어링: 역할(감정사) + 출력 형식(JSON 스키마) + 규칙을 글로 지정한다.
 *    카테고리·상태 코드는 enum 값을 반복문으로 넣어서, enum 을 바꾸면 프롬프트도 자동으로 바뀐다.
 *  - StringJoiner(", "): 사이에만 구분자를 넣어 "A, B, C" 를 만든다. StringBuilder: 문자열을 효율적으로 이어 붙임.
 *  - final class + private 생성자: 객체를 만들 필요 없는 static 도우미 모음(유틸리티 클래스).
 *  - 접근제한자 없음(package-private): com.sbada.ai 패키지 안에서만 쓰는 내부 클래스.
 */
final class AiPrompt {

    private AiPrompt() {
    }

    static String build(PriceSanitizer.Hint hint) {
        StringJoiner categories = new StringJoiner(", ");
        for (Category c : Category.values()) {
            categories.add(c.name() + "(" + c.label() + ")");
        }
        StringJoiner conditions = new StringJoiner(", ");
        for (ItemCondition c : ItemCondition.values()) {
            conditions.add(c.name() + "(" + c.label() + ")");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("너는 한국 중고거래 시세에 밝은 물건 감정사다. 사진 속 물건을 보고 ")
          .append("한국 중고 시장에서 실제로 거래될 만한 가격을 원(KRW) 단위로 추정해라.\n")
          .append("반드시 아래 형식의 JSON 객체 하나만 출력하고, 다른 문장이나 코드블록 표시는 쓰지 마라.\n")
          .append("{\n")
          .append("  \"title\": \"물건 이름(브랜드·모델을 알 수 있으면 포함, 25자 이내)\",\n")
          .append("  \"category\": \"다음 중 하나의 코드: ").append(categories).append("\",\n")
          .append("  \"condition\": \"다음 중 하나의 코드: ").append(conditions).append("\",\n")
          .append("  \"price_krw\": 정수,\n")
          .append("  \"price_min_krw\": 정수,\n")
          .append("  \"price_max_krw\": 정수,\n")
          .append("  \"confidence\": 0과 1 사이 소수,\n")
          .append("  \"reason\": \"가격 근거 1~2문장(브랜드, 사용감, 수요, 신품가 대비 비율)\",\n")
          .append("  \"tags\": [\"검색 키워드\", 최대 5개]\n")
          .append("}\n")
          .append("규칙: 물건을 알아보기 어렵으면 confidence를 0.4 이하로 낮춰라. ")
          .append("사람·개인정보는 언급하지 마라.\n");
        if (hint.title() != null || hint.category() != null || hint.condition() != null) {
            sb.append("판매자가 알려 준 정보(가능하면 따른다): ");
            if (hint.title() != null) {
                sb.append("이름=").append(hint.title()).append(" ");
            }
            if (hint.category() != null) {
                sb.append("카테고리=").append(hint.category().name()).append(" ");
            }
            if (hint.condition() != null) {
                sb.append("상태=").append(hint.condition().name());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 모델 출력에서 JSON 객체 부분만 꺼낸다(```json 코드블록이 붙어 와도 처리).
     * 첫 '{' 부터 마지막 '}' 까지 자른다. 못 찾으면 예외 → AiPriceService 가 다음 방법(대체 추정)으로 넘어간다.
     */
    static String extractJson(String text) {
        if (text == null) {
            throw new IllegalStateException("모델 응답이 비어 있습니다.");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("모델 응답에서 JSON을 찾지 못했습니다.");
        }
        return text.substring(start, end + 1);
    }
}
