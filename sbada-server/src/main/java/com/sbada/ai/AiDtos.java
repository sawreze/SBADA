package com.sbada.ai;

import java.util.Arrays;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * AI 가격 분석 API 의 요청/응답 JSON 모양.
 *
 * [프로그래밍 설명]
 *  - imageId 를 long 이 아니라 Long(객체)으로 둔 이유: JSON 에 imageId 가 없으면 null 이 되어 @NotNull 로 잡을 수 있다.
 *    (기본형 long 이면 없을 때 0 이 들어가 '빠뜨린 것'을 알 수 없다)
 *  - 응답의 숫자 반올림: Math.round(x * 100) / 100.0 → 소수 둘째 자리까지(0.3333 → 0.33).
 */
public final class AiDtos {

    private AiDtos() {
    }

    /** POST /api/ai/analyze 요청 본문. category·condition 은 문자열로 받아 Controller 에서 enum 으로 바꾼다. */
    public record AnalyzeRequest(
            @NotNull(message = "분석할 사진(imageId)이 필요합니다.") Long imageId,
            @Size(max = 60, message = "이름은 60자 이하입니다.") String title,
            String category,
            String condition) {
    }

    /** POST /api/ai/analyze 응답. 예시는 AiController.analyze 주석 참고. */
    public record AnalysisView(long analysisId, long imageId, String suggestedTitle,
                               String category, String categoryLabel, String condition, String conditionLabel,
                               int price, int priceMin, int priceMax, double confidence, String reason,
                               List<String> tags, String provider, String model, long latencyMs,
                               int marketSamples, Integer marketMedian, double marketWeight) {
        public static AnalysisView from(AiAnalysis a) {
            // DB 에는 태그를 "자전거,헬멧" 처럼 쉼표로 이어 한 칸에 저장했으므로 목록으로 다시 나눈다
            List<String> tags = a.getTags() == null || a.getTags().isBlank() ? List.of()
                    : Arrays.asList(a.getTags().split(","));
            return new AnalysisView(a.getId(), a.getImage().getId(), a.getSuggestedTitle(),
                    a.getCategory().name(), a.getCategory().label(),
                    a.getItemCondition().name(), a.getItemCondition().label(),
                    a.getPrice(), a.getPriceMin(), a.getPriceMax(),
                    Math.round(a.getConfidence() * 100) / 100.0, a.getReason(), tags,
                    a.getProvider(), a.getModel(), a.getLatencyMs(),
                    a.getMarketSamples(), a.getMarketMedian(), Math.round(a.getMarketWeight() * 100) / 100.0);
        }
    }

    /** 어떤 AI가 쓰이는지(키 값 자체는 내보내지 않는다) — GET /api/ai/status 응답 */
    public record AiStatus(String setting, String active, boolean googleKeyConfigured, String googleModel,
                           boolean ollamaReachable, String ollamaModel) {
    }
}
