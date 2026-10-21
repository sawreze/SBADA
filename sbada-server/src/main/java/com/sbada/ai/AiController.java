package com.sbada.ai;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.Category;
import com.sbada.common.ItemCondition;

import jakarta.validation.Valid;

/**
 * ┌─────────────────────────────────────────────────────────┐
 * │ AI(Gemma) 가격 분석 API   (기본 주소: /api/ai)              │
 * └─────────────────────────────────────────────────────────┘
 *  POST /api/ai/analyze   🔒  올린 사진으로 가격 분석 → 분석 번호(analysisId) + 추천 가격
 *  GET  /api/ai/status    공개 지금 어떤 AI 로 분석하는지(google / ollama / fallback)
 *
 * [프로그래밍 설명]
 *  - 가격은 클라이언트가 정하지 않는다. 서버가 분석해 DB(ai_analyses)에 저장하고, 물건 등록 때는
 *    analysisId 만 받아 그 기록의 가격을 쓴다 → 앱을 조작해 가격을 마음대로 넣는 일을 막는다.
 *  - 분석은 외부 AI 를 부르므로 오래 걸린다(실측 약 30초, 서버 제한 90초). 앱은 이 요청만 100초까지 기다린다.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiPriceService service;

    public AiController(AiPriceService service) {
        this.service = service;
    }

    /**
     * ■ AI 가격 분석   POST /api/ai/analyze   (🔒)
     *   올린 사진으로 AI 가격 분석. category·condition 을 보내면 그 값을 기준으로 다시 분석한다.
     *
     * 요청 본문 — AiDtos.AnalyzeRequest
     *   {
     *     "imageId": 33,              // 필수. POST /api/images 로 받은 '내가 올린' 사진 번호
     *     "title": "에어팟 프로 2세대", // 선택. 사용자가 적은 이름(힌트)
     *     "category": "DIGITAL",      // 선택. 사용자가 고친 카테고리 → AI 추측보다 우선
     *     "condition": "GOOD"         // 선택. NEW / LIKE_NEW / GOOD / FAIR / POOR
     *   }
     *
     * 처리 흐름 (AiPriceService.analyze)
     *   ① 사진이 내 것인지 확인(남의 사진이면 403)
     *   ② Gemma 에 사진 + 지시문(AiPrompt)을 보냄 → JSON 답을 받음
     *   ③ PriceSanitizer 로 값 보정(범위·반올림·카테고리 정규화)
     *   ④ 앱 안의 같은 종류·상태 물건 시세와 섞기(3개 이상일 때)
     *   ⑤ ai_analyses 테이블에 저장하고 결과를 돌려줌
     *   ※ Gemma 실패·미설정이면 '카테고리 기준가 × 상태 감가율'로 추정(provider = "fallback") → 등록이 멈추지 않는다.
     *
     * 응답 200 — AiDtos.AnalysisView
     *   {
     *     "analysisId": 129, "imageId": 129,
     *     "suggestedTitle": "로드 자전거 헬멧",
     *     "category": "SPORTS", "categoryLabel": "스포츠/레저",
     *     "condition": "FAIR", "conditionLabel": "사용감 많음",
     *     "price": 50000, "priceMin": 30000, "priceMax": 80000,
     *     "confidence": 0.2,                       // 0~1, AI 가 스스로 매긴 확신도
     *     "reason": "실제 제품 사진이 아닌 일러스트레이션이며 …",
     *     "tags": ["자전거헬멧","로드자전거"],
     *     "provider": "google", "model": "gemma-4-26b-a4b-it", "latencyMs": 31510,
     *     "marketSamples": 0, "marketMedian": null, "marketWeight": 0.0   // 앱 내 시세 보정 정보
     *   }
     *
     * 오류
     *   400 VALIDATION_FAILED(imageId 없음) / INVALID_CATEGORY / INVALID_CONDITION
     *   401 AUTH_REQUIRED
     *   403 NOT_IMAGE_OWNER(남이 올린 사진)
     *   404 IMAGE_NOT_FOUND
     */
    @PostMapping("/analyze")
    public AiDtos.AnalysisView analyze(@Valid @RequestBody AiDtos.AnalyzeRequest input, Authentication auth) {
        // 사용자가 준 힌트를 정리: 빈 문자열은 null('힌트 없음')로, 카테고리·상태는 엄격하게 enum 으로 변환
        PriceSanitizer.Hint hint = new PriceSanitizer.Hint(
                blankToNull(input.title()),
                input.category() == null || input.category().isBlank() ? null : Category.parseStrict(input.category()),
                input.condition() == null || input.condition().isBlank() ? null
                        : ItemCondition.parseStrict(input.condition()));
        // 서비스는 엔티티(AiAnalysis)를 돌려주고, Controller 가 응답 DTO 로 바꾼다
        return AiDtos.AnalysisView.from(service.analyze((Long) auth.getPrincipal(), input.imageId(), hint));
    }

    /**
     * ■ AI 상태   GET /api/ai/status   (공개)
     *
     * 응답 200 — AiDtos.AiStatus (API 키 '값'은 절대 내보내지 않고, 설정됐는지 true/false 만)
     *   {"setting":"google","active":"google","googleKeyConfigured":true,
     *    "googleModel":"gemma-4-26b-a4b-it","ollamaReachable":false,"ollamaModel":"gemma4:e4b"}
     *   setting = application.properties 의 sbada.ai.provider (auto/google/ollama/fallback)
     *   active  = 지금 실제로 먼저 시도할 AI (키·서버가 없으면 "fallback")
     *
     * 앱의 물건 등록 화면, scripts\ai-check.bat 이 이 값으로 Gemma 연결 여부를 확인한다.
     */
    @GetMapping("/status")
    public AiDtos.AiStatus status() {
        return service.status();
    }

    /** null 이거나 공백뿐이면 null, 아니면 앞뒤 공백을 지운 값 */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
