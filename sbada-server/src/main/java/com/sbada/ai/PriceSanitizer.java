package com.sbada.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.sbada.common.Category;
import com.sbada.common.ItemCondition;

/**
 * AI 응답 후처리(순수 함수 → 단위 테스트로 검증).
 *
 * ① 정규화 : 카테고리·상태를 enum으로, 가격을 정수로. 사용자가 직접 고른 값(hint)이 있으면 그것을 우선한다.
 * ② 범위 보정: min ≤ price ≤ max 가 되도록 맞추고, 범위가 없으면 ±20%
 * ③ 반올림  : 1만원 미만 500원, 10만원 미만 1,000원, 그 이상 5,000원 단위
 * ④ 시세 블렌딩: 앱 안에 같은 카테고리·상태 물건이 3개 이상이면
 *              최종가 = (1−α)·AI가 + α·중앙값,  α = 0.3 · min(1, n/10)
 *
 *   예) AI가 80,000원, 같은 종류 5건 중앙값 60,000원 → α = 0.3·0.5 = 0.15
 *       → 0.85·80,000 + 0.15·60,000 = 77,000원
 *
 * [프로그래밍 설명]
 *  - 순수 함수(pure function): 입력이 같으면 항상 같은 출력, DB·네트워크를 쓰지 않음 → 모든 메서드가 static.
 *    그래서 스프링 없이 JUnit 으로 바로 테스트할 수 있다(PriceSanitizerTest).
 *  - AI 출력은 믿을 수 없는 '외부 입력'이다: 가격이 "약 3만원" 같은 문자열, confidence 가 85(퍼센트)로 오는 등
 *    → toInt·toDouble 로 관대하게 해석하고, clamp 로 500원~5천만원 사이로 강제한다(방어적 프로그래밍).
 *  - record Hint/Result/MarketBlend: 계산 결과를 담는 불변 데이터 묶음.
 */
public final class PriceSanitizer {

    public static final int MIN_PRICE = 500;
    public static final int MAX_PRICE = 50_000_000;

    private PriceSanitizer() {
    }

    public record Hint(String title, Category category, ItemCondition condition) {
        public static Hint none() {
            return new Hint(null, null, null);
        }
    }

    public record Result(String title, Category category, ItemCondition condition, int price, int priceMin,
                         int priceMax, double confidence, String reason, List<String> tags) {
    }

    public record MarketBlend(int price, int priceMin, int priceMax, int samples, Integer median,
                              double weight, String reason) {
    }

    /** ①~③ 정규화·범위·반올림. raw = 모델이 낸 JSON 을 Map 으로 읽은 것, hint = 사용자가 고른 값(우선) */
    public static Result sanitize(Map<String, Object> raw, Hint hint) {
        Category category = hint.category() != null ? hint.category() : Category.parseLoose(raw.get("category"));
        ItemCondition condition = hint.condition() != null ? hint.condition()
                : ItemCondition.parseLoose(raw.get("condition"));

        int price = toInt(raw.get("price_krw"));
        if (price <= 0) {
            price = (int) Math.round(category.basePrice() * condition.factor());
        }
        price = clamp(price, MIN_PRICE, MAX_PRICE);
        int min = toInt(raw.get("price_min_krw"));
        int max = toInt(raw.get("price_max_krw"));
        int[] range = fixRange(price, min, max);

        double confidence = toDouble(raw.get("confidence"), 0.5);
        confidence = Math.max(0.0, Math.min(1.0, confidence > 1.0 ? confidence / 100.0 : confidence));

        String title = str(raw.get("title"));
        if (hint.title() != null && !hint.title().isBlank()) {
            title = hint.title().trim();
        }
        if (title == null || title.isBlank()) {
            title = category.label() + " 물건";
        }
        title = cut(title, 40);

        String reason = str(raw.get("reason"));
        if (reason == null || reason.isBlank()) {
            reason = "사진 속 물건의 종류와 상태를 바탕으로 추정했어요.";
        }
        reason = cut(reason, 300);

        List<String> tags = new ArrayList<>();
        if (raw.get("tags") instanceof List<?> list) {
            for (Object t : list) {
                String s = str(t);
                if (s != null && !s.isBlank() && tags.size() < 5) {
                    tags.add(cut(s.trim(), 15));
                }
            }
        }
        return new Result(title, category, condition, roundPrice(price), roundPrice(range[0]),
                roundPrice(range[1]), confidence, reason, tags);
    }

    /** AI 없이 추정: 카테고리 기준가 × 상태 감가율 */
    public static Result fallback(Hint hint, String why) {
        Category category = hint.category() != null ? hint.category() : Category.ETC;
        ItemCondition condition = hint.condition() != null ? hint.condition() : ItemCondition.GOOD;
        int price = (int) Math.round(category.basePrice() * condition.factor());
        String title = hint.title() != null && !hint.title().isBlank() ? cut(hint.title().trim(), 40)
                : category.label() + " 물건";
        String reason = String.format(Locale.KOREA, "%s '%s' 평균 중고가 %,d원 × 상태 '%s'(%.0f%%)로 추정했어요.",
                why, category.label(), category.basePrice(), condition.label(), condition.factor() * 100);
        int[] range = fixRange(price, 0, 0);
        return new Result(title, category, condition, roundPrice(price), roundPrice(range[0]),
                roundPrice(range[1]), 0.3, cut(reason, 300), List.of());
    }

    /** ④ 시세 블렌딩. 3건 미만이면 그대로. 중앙값(평균 대신)을 쓰는 이유: 터무니없는 가격 하나에 흔들리지 않게 */
    public static MarketBlend blend(Result r, List<Integer> marketPrices) {
        int n = marketPrices.size();
        if (n < 3) {
            return new MarketBlend(r.price(), r.priceMin(), r.priceMax(), n, null, 0.0, r.reason());
        }
        List<Integer> sorted = new ArrayList<>(marketPrices);
        sorted.sort(Integer::compareTo);
        int median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double alpha = 0.3 * Math.min(1.0, n / 10.0);
        int blended = roundPrice(clamp((int) Math.round((1 - alpha) * r.price() + alpha * median), MIN_PRICE, MAX_PRICE));
        double ratio = (double) blended / Math.max(1, r.price());
        int[] range = fixRange(blended, (int) Math.round(r.priceMin() * ratio), (int) Math.round(r.priceMax() * ratio));
        String reason = cut(r.reason() + String.format(Locale.KOREA,
                " (앱 내 같은 종류 %d건의 중앙값 %,d원을 %.0f%% 반영)", n, median, alpha * 100), 600);
        return new MarketBlend(blended, roundPrice(range[0]), roundPrice(range[1]), n, median, alpha, reason);
    }

    static int[] fixRange(int price, int min, int max) {
        if (min <= 0 || min > price) {
            min = (int) Math.round(price * 0.8);
        }
        if (max <= 0 || max < price) {
            max = (int) Math.round(price * 1.2);
        }
        // 범위가 너무 좁으면 최소 ±5%
        min = Math.min(min, (int) Math.round(price * 0.95));
        max = Math.max(max, (int) Math.round(price * 1.05));
        // 범위가 너무 넓으면 0.4배 ~ 2.5배로 제한
        min = Math.max(min, (int) Math.round(price * 0.4));
        max = Math.min(max, (int) Math.round(price * 2.5));
        return new int[] {clamp(min, MIN_PRICE / 2, MAX_PRICE), clamp(max, MIN_PRICE, MAX_PRICE)};
    }

    public static int roundPrice(int p) {
        int unit = p < 10_000 ? 500 : p < 100_000 ? 1_000 : 5_000;
        return Math.max(unit, (int) (Math.round((double) p / unit) * unit));
    }

    /** 숫자·"35,000원"·"3.5만원" 같은 문자열을 정수(원)로. 해석 못 하면 0 */
    static int toInt(Object o) {
        if (o instanceof Number n) {
            return (int) Math.min(Integer.MAX_VALUE, Math.round(n.doubleValue()));
        }
        if (o instanceof String s) {
            String digits = s.replaceAll("[^0-9.]", "");
            if (digits.isEmpty()) {
                return 0;
            }
            try {
                double v = Double.parseDouble(digits);
                if (s.contains("만")) {
                    v *= 10_000;
                }
                return (int) Math.min(Integer.MAX_VALUE, Math.round(v));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    static double toDouble(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
