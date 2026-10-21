package com.sbada;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.sbada.recommend.ScoreMath;

/** 추천 알고리즘의 수식이 설명(docs/algorithm.md)대로 움직이는지 확인한다. */
class ScoreMathTest {

    @Test
    void samePriceIsPerfectMatch() {
        assertEquals(1.0, ScoreMath.priceGauss(50_000, 50_000), 1e-9);
    }

    @Test
    void priceGaussMatchesDocumentedTable() {
        // 1.2배 ≈ 0.87, 1.5배 ≈ 0.51, 2배 ≈ 0.14
        assertEquals(0.87, ScoreMath.priceGauss(100_000, 120_000), 0.01);
        assertEquals(0.51, ScoreMath.priceGauss(100_000, 150_000), 0.01);
        assertEquals(0.14, ScoreMath.priceGauss(100_000, 200_000), 0.01);
    }

    @Test
    void priceGaussIsSymmetricAndScaleFree() {
        assertEquals(ScoreMath.priceGauss(10_000, 20_000), ScoreMath.priceGauss(20_000, 10_000), 1e-9);
        assertEquals(ScoreMath.priceGauss(10_000, 20_000), ScoreMath.priceGauss(100_000, 200_000), 1e-9);
    }

    @Test
    void closerPriceAlwaysScoresHigher() {
        assertTrue(ScoreMath.priceGauss(100_000, 105_000) > ScoreMath.priceGauss(100_000, 130_000));
        assertTrue(ScoreMath.priceGauss(100_000, 95_000) > ScoreMath.priceGauss(100_000, 70_000));
    }

    @Test
    void rangeOverlapIsIntersectionOverUnion() {
        assertEquals(1.0, ScoreMath.rangeOverlap(10, 20, 10, 20), 1e-9);
        assertEquals(0.0, ScoreMath.rangeOverlap(10, 20, 30, 40), 1e-9);
        assertEquals(5.0 / 15.0, ScoreMath.rangeOverlap(10, 20, 15, 25), 1e-9);
    }

    @Test
    void freshnessDecaysOverTwoWeeks() {
        assertEquals(1.0, ScoreMath.freshness(0), 1e-9);
        assertEquals(Math.exp(-1), ScoreMath.freshness(14), 1e-9);
    }

    @Test
    void conditionSimilarity() {
        assertEquals(1.0, ScoreMath.conditionSimilarity(3, 3), 1e-9);
        assertEquals(0.0, ScoreMath.conditionSimilarity(4, 0), 1e-9);
    }

    @Test
    void keywordHitIgnoresCaseAndSpaces() {
        assertEquals("닌텐도", ScoreMath.keywordHit("닌텐 도 스위치", List.of("닌텐도")));
        assertEquals("ipad", ScoreMath.keywordHit("Apple iPad 9", List.of("ipad")));
        assertEquals(null, ScoreMath.keywordHit("캠핑 의자", List.of("닌텐도")));
    }

    @Test
    void diffPercent() {
        assertEquals(-7, ScoreMath.diffPercent(150_000, 140_000));
        assertEquals(20, ScoreMath.diffPercent(100_000, 120_000));
    }
}
