package com.sbada.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.ApiException;
import com.sbada.common.Json;
import com.sbada.image.ImageService;
import com.sbada.image.ItemImage;
import com.sbada.image.ItemImageRepository;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;
import com.sbada.user.AppUserRepository;

/**
 * AI 가격 분석 파이프라인
 *   사진 → (1) Gemma 호출 → (2) JSON 해석·정규화 → (3) 범위·반올림 보정 → (4) 앱 내 시세 블렌딩 → 저장
 * Gemma 가 실패하거나 설정이 없으면 기준가 추정(fallback)으로 바꿔 '등록이 멈추지 않게' 한다.
 * 외부 호출이 길 수 있으므로 이 메서드 전체를 DB 트랜잭션으로 묶지 않는다.
 *
 * (POST /api/ai/analyze 가 이 클래스의 analyze() 를 부른다 — API 설명은 AiController 참고)
 *
 * [프로그래밍 설명]
 *  - 전략(Strategy) 패턴: GoogleGemmaClient·OllamaGemmaClient 가 같은 GemmaClient 인터페이스를 구현하므로
 *    chain() 이 만든 목록을 for 문으로 '앞에서부터 하나씩 시도'할 수 있다. 하나가 실패하면 catch 후 다음으로.
 *  - 설정값 sbada.ai.provider: google / ollama / fallback / auto(기본: 구글 키 있으면 구글 → 로컬 Ollama → 대체 추정)
 *  - TransactionTemplate: @Transactional 대신 '코드 블록 단위'로 트랜잭션을 거는 도구.
 *    AI 호출(수 초~수십 초) 동안 DB 연결을 붙잡지 않으려고, 저장하는 순간에만 tx.execute(…) 로 짧게 묶는다.
 *  - 람다 안에서는 '사실상 final' 변수만 쓸 수 있어서 finalResult 같은 복사본을 만든다(Java 규칙).
 *  - getReferenceById: DB 를 조회하지 않고 'id 만 가진 프록시'를 만들어 외래키 연결에만 쓴다(쿼리 절약).
 */
@Service
public class AiPriceService {

    private static final Logger log = LoggerFactory.getLogger(AiPriceService.class);

    private final String setting;
    private final GoogleGemmaClient google;
    private final OllamaGemmaClient ollama;
    private final ImageService images;
    private final ItemRepository items;
    private final AppUserRepository users;
    private final AiAnalysisRepository analyses;
    private final ItemImageRepository imageRepository;
    private final TransactionTemplate tx;

    public AiPriceService(@Value("${sbada.ai.provider}") String setting, GoogleGemmaClient google,
                          OllamaGemmaClient ollama, ImageService images, ItemRepository items,
                          AppUserRepository users, AiAnalysisRepository analyses,
                          ItemImageRepository imageRepository, TransactionTemplate tx) {
        this.setting = setting.trim().toLowerCase(Locale.ROOT);
        this.google = google;
        this.ollama = ollama;
        this.images = images;
        this.items = items;
        this.users = users;
        this.analyses = analyses;
        this.imageRepository = imageRepository;
        this.tx = tx;
    }

    /**
     * 사진 한 장을 분석해 AiAnalysis 를 저장하고 돌려준다.
     *  ① 내가 올린 사진인지 확인(아니면 403 NOT_IMAGE_OWNER) → 파일 바이트 읽기
     *  ② chain() 순서대로 Gemma 호출 → 응답 문자열에서 JSON 만 추출 → PriceSanitizer 로 값 정리
     *  ③ 모두 실패하면 카테고리·상태 기준가로 대체(fallback) — 등록은 절대 멈추지 않는다
     *  ④ 앱 안의 같은 카테고리·상태 실제 가격(최대 30개)과 섞어 보정(blend)
     *  ⑤ 걸린 시간(latency)·제공자·모델과 함께 저장
     */
    public AiAnalysis analyze(long userId, long imageId, PriceSanitizer.Hint hint) {
        ItemImage image = images.require(imageId);
        if (image.getUploader().getId() != userId) {
            throw ApiException.forbidden("NOT_IMAGE_OWNER", "내가 올린 사진만 분석할 수 있습니다.");
        }
        ImageService.ImageBytes bytes = images.load(imageId);

        long started = System.currentTimeMillis();
        PriceSanitizer.Result result = null;
        String provider = "fallback";
        String model = "category-base-price";
        String failure = null;
        for (GemmaClient client : chain()) {
            try {
                String text = client.generate(AiPrompt.build(hint), bytes.bytes(), bytes.contentType());
                Map<String, Object> raw = Json.readMap(AiPrompt.extractJson(text));
                result = PriceSanitizer.sanitize(raw, hint);
                provider = client.name();
                model = client.model();
                break;
            } catch (Exception e) {
                failure = client.name() + ": " + e.getClass().getSimpleName();
                log.warn("Gemma 호출 실패({}) - 다음 방법으로 넘어갑니다: {}", client.name(), e.getMessage());
            }
        }
        if (result == null) {
            String why = failure == null ? "AI 서버가 설정되지 않아" : "AI 응답을 받지 못해";
            result = PriceSanitizer.fallback(hint, why);
        }
        long latency = System.currentTimeMillis() - started;

        List<Integer> market = items.marketPrices(result.category(), result.condition(),
                List.of(ItemStatus.AVAILABLE, ItemStatus.TRADED), PageRequest.of(0, 30));
        PriceSanitizer.MarketBlend blend = PriceSanitizer.blend(result, market);

        final PriceSanitizer.Result finalResult = result;
        final String finalProvider = provider;
        final String finalModel = model;
        AiAnalysis saved = tx.execute(status -> analyses.save(new AiAnalysis(users.getReferenceById(userId),
                imageRepository.getReferenceById(imageId), finalResult, finalResult.price(), blend,
                finalProvider, finalModel, latency)));
        log.info("AI 가격 분석 #{} provider={} model={} price={} ({}ms)", saved.getId(), provider, model,
                saved.getPrice(), latency);
        return saved;
    }

    /** 설정에 따라 시도할 순서를 정한다. */
    List<GemmaClient> chain() {
        List<GemmaClient> chain = new ArrayList<>();
        switch (setting) {
            case "google" -> {
                if (google.isConfigured()) {
                    chain.add(google);
                }
            }
            case "ollama" -> chain.add(ollama);
            case "fallback" -> {
                // AI를 부르지 않는다
            }
            default -> { // auto
                if (google.isConfigured()) {
                    chain.add(google);
                }
                if (ollama.isReachable()) {
                    chain.add(ollama);
                }
            }
        }
        return chain;
    }

    /** GET /api/ai/status, /api/health 에 쓰는 현재 AI 상태(실제로 쓰일 제공자 = chain 의 첫 번째) */
    public AiDtos.AiStatus status() {
        List<GemmaClient> chain = chain();
        String active = chain.isEmpty() ? "fallback" : chain.get(0).name();
        return new AiDtos.AiStatus(setting, active, google.isConfigured(), google.model(),
                ollama.isReachable(), ollama.model());
    }

    /** 물건 등록 시 analysisId 검증: 없으면 404 ANALYSIS_NOT_FOUND, 남의 분석이면 403 NOT_ANALYSIS_OWNER */
    public AiAnalysis requireOwned(long analysisId, long userId) {
        AiAnalysis a = analyses.findById(analysisId)
                .orElseThrow(() -> ApiException.notFound("ANALYSIS_NOT_FOUND", "AI 분석 기록을 찾을 수 없습니다."));
        if (a.getUser().getId() != userId) {
            throw ApiException.forbidden("NOT_ANALYSIS_OWNER", "내가 요청한 분석만 사용할 수 있습니다.");
        }
        return a;
    }
}
