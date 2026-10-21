package com.sbada.recommend;

import java.util.Collection;
import java.util.Locale;

/**
 * 추천 점수에 쓰는 순수 함수 모음(서버·DB 없이 단위 테스트로 검증한다).
 * 시연 때 칠판에 그대로 쓸 수 있도록 식을 단순하게 유지했다. 자세한 설명: docs/algorithm.md
 *
 * [프로그래밍 설명]
 *  - 모든 함수는 0~1 사이 값을 돌려준다 → RecommendationService 가 가중치를 곱해 더하기 쉽다(정규화).
 *  - exp(−x): x 가 0 이면 1, 커질수록 0 에 가까워지는 '부드러운 감소' 곡선. 가격 차이·경과일에 쓴다.
 *  - log1p(x) = ln(1+x): 조회수 1000 과 2000 의 차이를 작게 눌러(로그 스케일) 인기 물건 하나가 독점하지 않게.
 *  - final class + private 생성자 + static 메서드 = 유틸리티 클래스(객체를 만들지 않음).
 */
public final class ScoreMath {

    /** 가격 유사도 곡선의 폭. 가격 비율이 1.2배면 0.87점, 1.5배면 0.51점, 2배면 0.14점 */
    public static final double PRICE_SIGMA = 0.35;
    /** 신선도 감쇠 시간(일). 14일이 지나면 약 0.37점 */
    public static final double FRESH_TAU_DAYS = 14.0;

    private ScoreMath() {
    }

    /**
     * 로그 가격 비율에 대한 가우시안: exp(−(ln(p2/p1))² / 2σ²)
     * 비율로 보므로 '1만원 vs 2만원'과 '10만원 vs 20만원'을 같은 거리로 본다.
     */
    public static double priceGauss(int p1, int p2) {
        if (p1 <= 0 || p2 <= 0) {
            return 0.0;
        }
        double d = Math.log((double) p2 / p1);
        return Math.exp(-(d * d) / (2 * PRICE_SIGMA * PRICE_SIGMA));
    }

    /** 두 AI 가격 범위가 얼마나 겹치는가(교집합 ÷ 합집합, IoU) */
    public static double rangeOverlap(int min1, int max1, int min2, int max2) {
        double inter = Math.min(max1, max2) - Math.max(min1, min2);
        double union = Math.max(max1, max2) - Math.min(min1, min2);
        if (inter <= 0 || union <= 0) {
            return 0.0;
        }
        return inter / union;
    }

    /** 가격 점수 = 0.75 × 가우시안 + 0.25 × 범위 겹침 */
    public static double priceSimilarity(int p1, int min1, int max1, int p2, int min2, int max2) {
        return 0.75 * priceGauss(p1, p2) + 0.25 * rangeOverlap(min1, max1, min2, max2);
    }

    /** 신선도 = exp(−경과일 / 14) */
    public static double freshness(double ageDays) {
        return Math.exp(-Math.max(0, ageDays) / FRESH_TAU_DAYS);
    }

    /** 상태 유사도 = 1 − |등급차| / 4  (새 상품 4 … 고장 0) */
    public static double conditionSimilarity(int rank1, int rank2) {
        return 1.0 - Math.abs(rank1 - rank2) / 4.0;
    }

    /** 인기도 = ln(1 + 조회 + 3×교환요청) ÷ ln(1 + 후보 중 최대값) */
    public static double popularity(int views, int requests, double maxRaw) {
        double raw = Math.log1p(views + 3.0 * requests);
        double max = Math.log1p(Math.max(1.0, maxRaw));
        return Math.min(1.0, raw / max);
    }

    public static double popularityRaw(int views, int requests) {
        return views + 3.0 * requests;
    }

    /** 제목에 관심 키워드가 들어 있으면 그 키워드를 돌려준다. */
    public static String keywordHit(String title, Collection<String> keywords) {
        if (title == null || keywords == null) {
            return null;
        }
        String t = title.toLowerCase(Locale.ROOT).replace(" ", "");
        for (String k : keywords) {
            String kk = k.toLowerCase(Locale.ROOT).replace(" ", "");
            if (!kk.isEmpty() && t.contains(kk)) {
                return k;
            }
        }
        return null;
    }

    /** 가격 차이(%) = (후보 − 기준) ÷ 기준 × 100 */
    public static int diffPercent(int base, int other) {
        if (base <= 0) {
            return 0;
        }
        return (int) Math.round((other - base) * 100.0 / base);
    }

    public static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
