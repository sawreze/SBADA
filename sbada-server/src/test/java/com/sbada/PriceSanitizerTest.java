package com.sbada;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sbada.ai.PriceSanitizer;
import com.sbada.common.Category;
import com.sbada.common.ItemCondition;

/** Gemma 응답이 어떤 모양이어도 서버가 '말이 되는 가격'으로 정리하는지 확인한다. */
class PriceSanitizerTest {

    @Test
    void normalResponseIsRoundedAndKept() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("title", "에어팟 프로 2세대");
        raw.put("category", "DIGITAL");
        raw.put("condition", "GOOD");
        raw.put("price_krw", 152_300);
        raw.put("price_min_krw", 130_000);
        raw.put("price_max_krw", 170_000);
        raw.put("confidence", 0.82);
        raw.put("reason", "시세 반영");
        raw.put("tags", List.of("에어팟", "애플"));
        PriceSanitizer.Result r = PriceSanitizer.sanitize(raw, PriceSanitizer.Hint.none());
        assertEquals(Category.DIGITAL, r.category());
        assertEquals(ItemCondition.GOOD, r.condition());
        assertEquals(150_000, r.price()); // 10만원 이상은 5,000원 단위 반올림
        assertEquals(130_000, r.priceMin());
        assertEquals(170_000, r.priceMax());
        assertEquals(List.of("에어팟", "애플"), r.tags());
    }

    @Test
    void brokenRangeIsRepaired() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("category", "book");
        raw.put("price_krw", "약 2만원");
        raw.put("price_min_krw", 50_000); // 가격보다 큰 최소값
        raw.put("confidence", 85);         // 퍼센트로 답한 경우
        PriceSanitizer.Result r = PriceSanitizer.sanitize(raw, PriceSanitizer.Hint.none());
        assertEquals(Category.BOOK, r.category());
        assertEquals(20_000, r.price());
        assertTrue(r.priceMin() <= r.price() && r.price() <= r.priceMax());
        assertEquals(0.85, r.confidence(), 1e-9);
    }

    @Test
    void userHintWinsOverModelGuess() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("category", "ETC");
        raw.put("condition", "POOR");
        raw.put("price_krw", 10_000);
        PriceSanitizer.Result r = PriceSanitizer.sanitize(raw,
                new PriceSanitizer.Hint("캠핑 의자", Category.SPORTS, ItemCondition.LIKE_NEW));
        assertEquals(Category.SPORTS, r.category());
        assertEquals(ItemCondition.LIKE_NEW, r.condition());
        assertEquals("캠핑 의자", r.title());
    }

    @Test
    void fallbackUsesBasePriceTimesConditionFactor() {
        PriceSanitizer.Result r = PriceSanitizer.fallback(
                new PriceSanitizer.Hint(null, Category.DIGITAL, ItemCondition.FAIR), "테스트");
        assertEquals(75_000, r.price()); // 150,000 × 0.5
        assertEquals(0.3, r.confidence(), 1e-9);
    }

    @Test
    void marketBlendNeedsAtLeastThreeSamples() {
        PriceSanitizer.Result r = PriceSanitizer.fallback(
                new PriceSanitizer.Hint(null, Category.BOOK, ItemCondition.GOOD), "테스트");
        assertEquals(r.price(), PriceSanitizer.blend(r, List.of(100_000, 100_000)).price());
    }

    @Test
    void marketBlendMovesTowardMedian() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("category", "HOBBY");
        raw.put("price_krw", 100_000);
        PriceSanitizer.Result r = PriceSanitizer.sanitize(raw, PriceSanitizer.Hint.none());
        // 10건 → α = 0.3 → 0.7·100,000 + 0.3·200,000 = 130,000
        List<Integer> market = List.of(200_000, 200_000, 200_000, 200_000, 200_000,
                200_000, 200_000, 200_000, 200_000, 200_000);
        PriceSanitizer.MarketBlend b = PriceSanitizer.blend(r, market);
        assertEquals(130_000, b.price());
        assertEquals(10, b.samples());
        assertEquals(0.3, b.weight(), 1e-9);
    }
}
