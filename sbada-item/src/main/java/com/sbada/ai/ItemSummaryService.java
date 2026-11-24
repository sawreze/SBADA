package com.sbada.ai;

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.ApiException;
import com.sbada.common.Category;
import com.sbada.common.Json;
import com.sbada.image.ImageService;
import com.sbada.item.Item;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;

/**
 * AI 상품 요약(2026-10-22): 물건 상세 '거래 희망 장소' 지도 바로 위에 보여 줄 2~3문장.
 *
 * 처리 순서 — GET /api/items/{id}/summary
 *   ① 물건 확인(없거나 남의 보류 물건이면 404)
 *   ② 저장된 요약이 있으면 그대로(V15 items.ai_summary) → 두 번째부터는 기다리지 않는다
 *   ③ 없으면 Gemma(설정한 순서, AiPriceService.chain)에 대표 사진 + 제목 · 설명 · 상태 · 가격 · 바꾸고 싶은 종류를 보내
 *      JSON {"summary": "..."} 을 받는다 → 저장
 *   ④ AI 가 없거나 실패하면 글 정보로 만든 기본 요약(fallback). 저장하지 않아 다음에 AI 로 다시 시도한다
 *
 * [프로그래밍 설명]
 *  - Gemma 호출(30초 안팎)은 트랜잭션 밖에서 한다: DB 연결을 오래 붙잡지 않으려고. 읽기 · 저장만 TransactionTemplate 로 짧게.
 *  - 프롬프트에 '주어진 정보에 없는 사실은 쓰지 마'를 넣어 지어낸 내용(환각)을 줄인다.
 */
@Service
public class ItemSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ItemSummaryService.class);

    private final AiPriceService ai;
    private final ItemRepository items;
    private final ImageService images;
    private final TransactionTemplate tx;

    public ItemSummaryService(AiPriceService ai, ItemRepository items, ImageService images, TransactionTemplate tx) {
        this.ai = ai;
        this.items = items;
        this.images = images;
        this.tx = tx;
    }

    /** 응답: {"itemId":5,"summary":"…","provider":"google|ollama|fallback","cached":true} */
    public record Summary(long itemId, String summary, String provider, boolean cached) {
    }

    /** 요약에 쓰는 물건 정보(트랜잭션 안에서 꺼내 둔 값) */
    record Facts(String title, String description, String category, String condition, int price, String wish,
                 Long imageId, String savedSummary, String savedProvider) {
    }

    public Summary summarize(long itemId, Long viewerId) {
        Facts f = tx.execute(status -> {
            Item item = items.findById(itemId)
                    .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다."));
            if (item.getStatus() == ItemStatus.HELD && (viewerId == null || !item.isOwnedBy(viewerId))) {
                throw ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다.");
            }
            String wish = item.getWishCategories().stream().map(Category::label).collect(Collectors.joining(", "));
            Long imageId = item.getImages().isEmpty() ? null : item.getImages().get(0).getId();
            return new Facts(item.getTitle(), item.getDescription(), item.getCategory().label(),
                    item.getItemCondition().label(), item.getPrice(), wish, imageId,
                    item.getAiSummary(), item.getAiSummaryProvider());
        });
        if (f.savedSummary() != null && !f.savedSummary().isBlank()) {
            return new Summary(itemId, f.savedSummary(), f.savedProvider(), true);
        }
        for (GemmaClient client : ai.chain()) {
            try {
                ImageService.ImageBytes img = f.imageId() == null ? null : images.load(f.imageId());
                String text = client.generate(prompt(f), img == null ? null : img.bytes(),
                        img == null ? null : img.contentType());
                String summary = clean(parse(text));
                if (summary.isEmpty()) {
                    continue;
                }
                tx.executeWithoutResult(status -> items.findById(itemId)
                        .ifPresent(item -> item.saveSummary(summary, client.name())));
                log.info("AI 상품 요약({}) #{} '{}'", client.name(), itemId, f.title());
                return new Summary(itemId, summary, client.name(), false);
            } catch (Exception e) {
                log.warn("AI 상품 요약 실패({}): {}", client.name(), e.getMessage());
            }
        }
        return new Summary(itemId, fallback(f), "fallback", false);
    }

    static String prompt(Facts f) {
        return """
                너는 한국 중고 물물교환 앱 '스바다'에서 물건 상세 화면의 요약을 쓰는 도우미다.
                아래 물건 정보와 사진을 보고, 이 물건을 처음 보는 이웃이 바로 이해하도록 한국어 2~3문장으로 요약해라.
                - 무슨 물건인지, 상태가 어떤지, 어떤 사람에게 맞는지를 담는다.
                - 주어진 정보와 사진에 없는 사실(브랜드 · 구매 시기 · 하자 등)은 지어내지 않는다.
                - 가격 숫자와 '바꾸고 싶은 종류'는 화면에 따로 있으니 반복하지 않아도 된다.
                - 120자 안팎, 존댓말(~요)로 쓴다.
                제목: %s
                종류: %s
                상태: %s
                AI 분석 가치: %,d원
                바꾸고 싶은 종류: %s
                설명: %s
                JSON 하나만 답해라(설명 문장 금지): {"summary": "요약 문장"}
                """.formatted(f.title(), f.category(), f.condition(), f.price(),
                f.wish().isEmpty() ? "없음" : f.wish(), f.description() == null ? "" : f.description());
    }

    static String parse(String text) throws Exception {
        Map<String, Object> raw = Json.readMap(AiPrompt.extractJson(text));
        Object s = raw.get("summary");
        return s == null ? "" : s.toString();
    }

    /** 줄바꿈 · 앞뒤 따옴표를 정리하고 길이를 맞춘다(DB 칸 500자) */
    static String clean(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    /** AI 없이 글 정보로 만든 기본 요약 */
    static String fallback(Facts f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.category()).append(" 종류의 '").append(f.title()).append("'이에요. ");
        sb.append("상태는 '").append(f.condition()).append("'이고, AI 분석 가치는 ")
                .append(String.format("%,d원", f.price())).append("이에요.");
        if (!f.wish().isEmpty()) {
            sb.append(' ').append(f.wish()).append(" 물건과 바꾸고 싶어 해요.");
        }
        return sb.toString();
    }
}
