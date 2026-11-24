package com.sbada.recommend;

import java.util.List;

import com.sbada.item.ItemDtos;

/** 추천 API 응답 JSON 모양. 예시는 RecommendController 맨 위 주석 참고. */
public final class RecommendDtos {

    private RecommendDtos() {
    }

    /** 점수 구성 요소 하나: value(0~1) × weight 가 최종 점수에 더해진다. value 는 소수 셋째 자리까지 반올림. */
    public record Factor(String key, String label, double value, double weight) {
        public static Factor of(String key, String label, double value, double weight) {
            return new Factor(key, label, Math.round(value * 1000) / 1000.0, weight);
        }
    }

    /**
     * score: 0~100 정수(시연 화면의 '매칭 점수')
     * requestStatus: 이미 이 물건에 교환 요청을 보냈다면 REQUESTED
     */
    public record Recommendation(ItemDtos.ItemView item, int score, int priceDiffPercent,
                                 List<Factor> factors, List<String> reasons, String requestStatus) {
    }

    /** GET /api/items/{id}/matches 응답: 기준 물건 + 알고리즘 이름 + 후보 수 + 추천 목록 */
    public record MatchResult(ItemDtos.ItemView base, String algorithm, int candidates,
                              List<Recommendation> matches) {
    }

    /** GET /api/feed 응답 */
    public record FeedResult(String algorithm, int candidates, List<Recommendation> items) {
    }
}
