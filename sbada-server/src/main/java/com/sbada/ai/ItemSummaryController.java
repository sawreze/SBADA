package com.sbada.ai;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * ■ AI 상품 요약   GET /api/items/{id}/summary   (🔒)   물건 상세 '거래 희망 장소' 지도 바로 위 칸
 *
 * 응답 200 — {"itemId":5,"summary":"닌텐도 스위치 게임 카드예요. …","provider":"google","cached":false}
 *   provider: 요약을 쓴 AI(google · ollama), AI 가 없거나 실패하면 fallback(글 정보로 만든 기본 요약)
 *   cached  : 이미 만들어 둔 요약이면 true(바로 응답). 처음이면 Gemma 를 불러 30초 안팎 걸린다
 * 오류: 401(로그인 필요) · 404 ITEM_NOT_FOUND(없는 물건 · 남의 보류 물건)
 *
 * 로그인을 요구하는 이유: Gemma 호출에 비용 · 시간이 들어 아무나 반복 호출하지 못하게(SecurityConfig 에서
 * "/api/items/*" 만 공개라 "/api/items/5/summary" 는 자동으로 보호된다).
 */
@RestController
public class ItemSummaryController {

    private final ItemSummaryService service;

    public ItemSummaryController(ItemSummaryService service) {
        this.service = service;
    }

    @GetMapping("/api/items/{id}/summary")
    public ItemSummaryService.Summary summary(@PathVariable long id, Authentication auth) {
        return service.summarize(id, (Long) auth.getPrincipal());
    }
}
