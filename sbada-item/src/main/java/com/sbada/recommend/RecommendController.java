package com.sbada.recommend;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ┌───────────────────────────────────────────────────────────┐
 * │ 추천 API   (🔒 로그인 필요 — '나'를 알아야 추천할 수 있어서)     │
 * └───────────────────────────────────────────────────────────┘
 *  GET /api/items/{id}/matches?limit=20   내 물건 {id} 와 바꿀 만한 '비슷한 가격대' 물건(점수순)
 *  GET /api/feed?limit=30                 홈 'AI 추천' 피드(나를 위한 교환 추천, 점수순)
 *
 * [프로그래밍 설명]
 *  - 계산은 모두 RecommendationService 가 한다(점수 식은 그 클래스 맨 위 주석, docs/algorithm.md).
 *  - limit 은 Math.max(1, Math.min(limit, 상한)) 으로 잘라 너무 크거나 0 이하인 값을 막는다(서버 부담 방지).
 *  - 응답에 점수뿐 아니라 '왜 이 점수인지'(factors: 요소별 값·가중치, reasons: 한 줄 이유)를 함께 보낸다
 *    → 앱이 '왜 추천됐나요?' 화면과 매칭 점수 막대를 그대로 그릴 수 있다(설명 가능한 추천).
 *
 *  Recommendation 한 개의 JSON 예:
 *   {
 *     "item": { ItemView… },                  // 추천된 물건
 *     "score": 87,                            // 0~100
 *     "priceDiffPercent": -7,                 // 기준 물건 대비 가격 차이(%)
 *     "factors": [
 *       {"key":"price","label":"가격 유사도","value":0.93,"weight":0.4},
 *       {"key":"want","label":"내 선호","value":1.0,"weight":0.25},
 *       {"key":"mutual","label":"상대 선호(상호성)","value":0.8,"weight":0.2},
 *       {"key":"fresh","label":"신선도","value":0.95,"weight":0.1},
 *       {"key":"condition","label":"상태 유사도","value":0.75,"weight":0.05}
 *     ],
 *     "reasons": ["비슷한 가격대 (차이 -7%)","내가 원하는 취미/게임/음반"],
 *     "requestStatus": null                   // 이미 교환 요청을 보냈으면 "REQUESTED"
 *   }
 *   score ≈ Σ(value × weight) × 100 (다양성 보정 전)
 */
@RestController
public class RecommendController {

    private final RecommendationService service;

    public RecommendController(RecommendationService service) {
        this.service = service;
    }

    /**
     * ■ 가격 매칭   GET /api/items/{id}/matches?limit=20   (🔒, 내 물건만)
     *   내 물건과 비슷한 가격대의 교환 후보(점수·근거 포함)
     *
     *   1단계: 내 물건 가격의 0.5배~2배, 교환 가능, 남의 물건만 DB 에서 후보로 거른다(최대 300개)
     *   2단계: 0.40·가격유사도 + 0.25·내 선호 + 0.20·상대가 내 물건 종류를 원하는 정도 + 0.10·신선도 + 0.05·상태 유사도
     *   3단계: 같은 카테고리만 몰리지 않게 다양성 보정(같은 종류가 k개 뽑혔으면 0.85^k 배)
     *
     * 응답 200 — RecommendDtos.MatchResult
     *   {"base": {내 물건 ItemView}, "algorithm": "…", "candidates": 12, "matches": [Recommendation…]}
     * 오류: 401, 403 NOT_OWNER(남의 물건 번호), 404 ITEM_NOT_FOUND
     * (물건 등록 직후 '비슷한 가격대 매칭' 화면, 상세 화면의 '내 ○○와 매칭 N점' 카드)
     */
    @GetMapping("/api/items/{id}/matches")
    public RecommendDtos.MatchResult matches(@PathVariable long id,
                                             @RequestParam(defaultValue = "20") int limit,
                                             Authentication auth) {
        return service.matches(id, (Long) auth.getPrincipal(), Math.max(1, Math.min(limit, 50)));
    }

    /**
     * ■ 홈 추천 피드   GET /api/feed?limit=30   (🔒)
     *   온보딩·행동 학습 선호도를 반영한 홈 피드
     *
     *   0.40·선호도(온보딩에서 고른 종류 + 조회·채팅·교환으로 학습) + 0.30·가격 적합도(내 물건 또는 고른 가격대와 얼마나 맞나)
     *   + 0.15·신선도(최근 등록일수록) + 0.15·인기도(조회·교환 요청 수) → 다양성 보정
     *   온보딩도 행동도 없으면(콜드 스타트) 선호도를 0.5 로 두고 나머지 요소로 정렬한다.
     *
     * 응답 200 — RecommendDtos.FeedResult
     *   {"algorithm": "…", "candidates": 15, "items": [Recommendation…]}
     *   factors 의 key: preference / price / fresh / popularity
     * (앱 홈의 'AI 추천' 목록, 동네지도 핀의 매치도 %)
     */
    @GetMapping("/api/feed")
    public RecommendDtos.FeedResult feed(@RequestParam(defaultValue = "30") int limit,
                                         @RequestParam(required = false) String area, Authentication auth) {
        // area(선택): 홈 왼쪽 위 범위 — '서울 마포구 망원동'(내 동네) · '서울 마포구'(시 · 구) · '서울'(도) · 없음(전국)
        return service.feed((Long) auth.getPrincipal(), Math.max(1, Math.min(limit, 60)), area);
    }

    /**
     * ■ 매치도 점수   GET /api/feed/scores?ids=12,34,56   (🔒)   동네지도 핀의 %
     *   지도에 보이는 물건들만 홈 추천과 같은 식으로 점수를 매겨 돌려준다(최대 200개, 내 물건 · 교환 불가 물건은 빠짐).
     * 응답 200 — {"scores": {"12": 87, "34": 72}}
     */
    @GetMapping("/api/feed/scores")
    public java.util.Map<String, java.util.Map<Long, Integer>> scores(@RequestParam java.util.List<Long> ids,
                                                                    Authentication auth) {
        java.util.List<Long> limited = ids.stream().distinct().limit(200).toList();
        return java.util.Map.of("scores", service.scoresFor((Long) auth.getPrincipal(), limited));
    }
}
