package com.sbada.ai;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.Json;
import com.sbada.exchange.ExchangeService;
import com.sbada.image.ImageService;
import com.sbada.item.Item;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;

/**
 * 콘텐츠 검사(물건을 올린 뒤). 걸리면 물건을 보류(HELD)하고 올린 사람에게 알림(ITEM_HELD)을 보낸다.
 *
 *  ① 금칙어(ProfanityFilter): 제목 · 설명의 욕설 · 비속어 → 바로 보류
 *  ② Gemma: 대표 사진 + 제목 · 설명을 보고 선정성(SEXUAL) · 혐오(HATE) · 폭력/잔인(VIOLENCE) · 욕설(PROFANITY) 판정
 *  ③ 통과하면 [onPass](비슷한 가격대 사용자에게 매칭 알림)를 실행한다 → 보류된 물건은 남에게 알려지지 않는다.
 *  AI 가 없거나 응답을 못 받으면 ①만 적용하고 통과시킨다(등록을 막지 않는다).
 *
 * 등록 응답을 늦추지 않도록 커밋 뒤 가상 스레드에서 돈다(Gemma 는 20~40초 걸릴 수 있다).
 */
@Service
public class ModerationService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final AiPriceService ai;
    private final ItemRepository items;
    private final ImageService images;
    private final NotificationService notifications;
    private final ExchangeService exchanges;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ModerationService(AiPriceService ai, ItemRepository items, ImageService images,
                             NotificationService notifications, ExchangeService exchanges, TransactionTemplate tx) {
        this.ai = ai;
        this.items = items;
        this.images = images;
        this.notifications = notifications;
        this.exchanges = exchanges;
        this.tx = tx;
    }

    /** 검사 결과 */
    public record Verdict(boolean allowed, String category, String reason) {
        static Verdict ok() {
            return new Verdict(true, "OK", null);
        }
    }

    /** 새 물건 검사(비동기). 통과하면 onPass 실행 */
    public void reviewNewItem(long itemId, Runnable onPass) {
        executor.submit(() -> {
            try {
                Verdict v = review(itemId);
                if (v.allowed()) {
                    onPass.run();
                } else {
                    hold(itemId, v);
                }
            } catch (Exception e) {
                log.warn("콘텐츠 검사 실패(물건 #{}) — 통과로 처리: {}", itemId, e.getMessage());
                onPass.run();
            }
        });
    }

    /** 검사만(동기). 테스트 · 재검사용 */
    public Verdict review(long itemId) {
        record Snapshot(String title, String description, Long imageId) {
        }
        Snapshot s = tx.execute(status -> {
            Item item = items.findById(itemId).orElse(null);
            if (item == null) {
                return null;
            }
            Long img = item.getImages().isEmpty() ? null : item.getImages().get(0).getId();
            // 설명 + 정든 물건에게 보내는 메시지(목록에 그대로 보이므로 같이 검사)
            String text = item.getDescription() == null ? "" : item.getDescription();
            if (item.getFarewellMessage() != null) {
                text = (text + "\n메시지: " + item.getFarewellMessage()).trim();
            }
            return new Snapshot(item.getTitle(), text, img);
        });
        if (s == null) {
            return Verdict.ok();
        }
        String bad = ProfanityFilter.find(s.title(), s.description());
        if (bad != null) {
            return new Verdict(false, "PROFANITY", "제목 · 설명 · 메시지에 욕설 · 비속어가 있어요.");
        }
        return askGemma(s.title(), s.description(), s.imageId());
    }

    private Verdict askGemma(String title, String description, Long imageId) {
        if (imageId == null) {
            return Verdict.ok();
        }
        ImageService.ImageBytes bytes = images.load(imageId);
        for (GemmaClient client : ai.chain()) {
            try {
                String text = client.generate(prompt(title, description), bytes.bytes(), bytes.contentType());
                Map<String, Object> raw = Json.readMap(AiPrompt.extractJson(text));
                Verdict v = parse(raw);
                log.info("콘텐츠 검사({}) '{}' → {} {}", client.name(), title, v.category(), v.allowed() ? "통과" : "보류");
                return v;
            } catch (Exception e) {
                log.warn("콘텐츠 검사 Gemma 호출 실패({}): {}", client.name(), e.getMessage());
            }
        }
        return Verdict.ok(); // AI 없음 · 실패 → 금칙어 검사만 적용
    }

    static String prompt(String title, String description) {
        return """
                너는 한국 중고 물물교환 앱 '스바다'의 게시물 검수 담당이다.
                아래 물건 사진과 글이 다른 사용자에게 보여도 되는지 판단해라.
                막아야 하는 것:
                 - SEXUAL: 노출 · 성적인 사진이나 글
                 - HATE: 특정 집단 비하 · 혐오 표현 · 혐오 상징
                 - VIOLENCE: 피 · 잔인한 장면 · 무기로 위협하는 장면 · 시체 · 사체
                 - PROFANITY: 욕설 · 비속어 · 모욕
                일반적인 물건 사진(전자기기 · 옷 · 책 · 가구 · 장난감 등)은 OK 다. 확실하지 않으면 OK 로 판단한다.
                제목: %s
                설명: %s
                JSON 하나만 답해라(설명 문장 금지):
                {"allowed": true 또는 false, "category": "OK|SEXUAL|HATE|VIOLENCE|PROFANITY", "reason": "사용자에게 보여 줄 한 문장(한국어)"}
                """.formatted(title == null ? "" : title, description == null ? "" : description);
    }

    static Verdict parse(Map<String, Object> raw) {
        Object allowed = raw.get("allowed");
        String category = raw.get("category") == null ? "OK" : raw.get("category").toString().trim().toUpperCase();
        boolean ok = allowed instanceof Boolean b ? b : !"false".equalsIgnoreCase(String.valueOf(allowed));
        if ("OK".equals(category)) {
            ok = true;
        }
        if (ok) {
            return Verdict.ok();
        }
        String reason = raw.get("reason") == null ? "" : raw.get("reason").toString().trim();
        if (reason.isEmpty()) {
            reason = switch (category) {
                case "SEXUAL" -> "선정적인 사진이나 내용이 있어요.";
                case "HATE" -> "혐오를 줄 수 있는 사진이나 내용이 있어요.";
                case "VIOLENCE" -> "잔인하거나 폭력적인 사진이 있어요.";
                default -> "욕설 · 비속어가 있어요.";
            };
        }
        return new Verdict(false, category, reason);
    }

    private void hold(long itemId, Verdict v) {
        tx.executeWithoutResult(status -> {
            Item item = items.findById(itemId).orElse(null);
            if (item == null || item.getStatus() != ItemStatus.AVAILABLE) {
                return;
            }
            item.hold(v.reason());
            exchanges.cancelAllFor(item, "물건이 보류되어 교환 요청이 취소되었어요.");
            notifications.notify(item.getOwner().getId(), NotificationType.ITEM_HELD, "물건이 보류되었어요",
                    String.format("'%s' — %s 다른 사람에게는 보이지 않아요. 내용을 고쳐 다시 올려 주세요.", item.getTitle(), v.reason()),
                    "ITEM", item.getId());
            log.info("물건 #{} 보류({}): {}", itemId, v.category(), v.reason());
        });
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
