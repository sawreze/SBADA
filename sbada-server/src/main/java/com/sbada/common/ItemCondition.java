package com.sbada.common;

import java.util.Locale;

/**
 * 물건 상태. factor 는 기준가에 곱하는 감가율, rank 는 상태 유사도 계산(0~4)에 쓴다.
 *
 * [프로그래밍 설명]
 *  - Category 와 같은 '필드가 있는 enum'. 예: GOOD 의 factor 0.70 → AI 없이 추정할 때 기준가 × 0.7.
 *  - rank 는 숫자 비교가 필요할 때 쓴다: 두 물건 상태 차이 |rank1 - rank2| 가 작을수록 '상태가 비슷'(매칭 점수 5%).
 *  - API 로 주고받는 값은 이름("GOOD")이고, 화면에 보여 줄 글자는 label("사용감 적음")이다.
 */
public enum ItemCondition {
    NEW("새 상품", 1.00, 4),
    LIKE_NEW("거의 새것", 0.85, 3),
    GOOD("사용감 적음", 0.70, 2),
    FAIR("사용감 많음", 0.50, 1),
    POOR("고장/부품용", 0.25, 0);

    private final String label;
    private final double factor;
    private final int rank;

    ItemCondition(String label, double factor, int rank) {
        this.label = label;
        this.factor = factor;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    public double factor() {
        return factor;
    }

    public int rank() {
        return rank;
    }

    /** AI 응답처럼 형식을 믿을 수 없는 값 → 최대한 맞춰 보고, 모르면 GOOD(예외 없음) */
    public static ItemCondition parseLoose(Object raw) {
        if (raw == null) {
            return GOOD;
        }
        String s = raw.toString().trim();
        for (ItemCondition c : values()) {
            if (c.name().equalsIgnoreCase(s) || c.label.equals(s)) {
                return c;
            }
        }
        // "like new", "like-new" → "LIKE_NEW" 로 바꿔 다시 비교
        String upper = s.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (ItemCondition c : values()) {
            if (upper.equals(c.name())) {
                return c;
            }
        }
        return GOOD;
    }

    /** 사용자 요청 값 → 정확히 맞아야 하고, 아니면 400 INVALID_CONDITION */
    public static ItemCondition parseStrict(String raw) {
        try {
            return ItemCondition.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("INVALID_CONDITION", "알 수 없는 상품 상태입니다: " + raw);
        }
    }
}
