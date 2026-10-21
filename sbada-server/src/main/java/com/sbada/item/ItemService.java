package com.sbada.item;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.ai.AiAnalysis;
import com.sbada.ai.AiPriceService;
import com.sbada.ai.ModerationService;
import com.sbada.ai.PriceSanitizer;
import com.sbada.common.AfterCommit;
import com.sbada.common.ApiException;
import com.sbada.common.Category;
import com.sbada.common.HangulSearch;
import com.sbada.common.ItemCondition;
import com.sbada.exchange.ExchangeService;
import com.sbada.image.ItemImage;
import com.sbada.image.ItemImageRepository;
import com.sbada.recommend.RecommendationService;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;
import com.sbada.user.PreferenceService;

/**
 * 물건 등록·조회·수정·내리기의 업무 규칙(ItemController 가 부른다).
 *
 * [프로그래밍 설명]
 *  - 다른 패키지의 서비스(AI·선호도·교환·추천)를 생성자로 주입받아 함께 쓴다 → 서비스끼리 협력.
 *  - TransactionTemplate: @Transactional 대신 '코드로' 트랜잭션 범위를 정하는 도구.
 *      tx.execute(status -> { … }) 의 람다 안만 하나의 트랜잭션이 된다.
 *    create() 는 오래 걸리는 AI 호출(최대 90초)을 트랜잭션 밖에서 먼저 하고, DB 저장만 짧은 트랜잭션으로 묶는다.
 *    (AI 를 기다리는 동안 DB 연결·잠금을 붙잡고 있지 않게 하려는 것)
 *  - require / requireOwned 처럼 '찾고 없으면 예외'를 메서드로 모아 권한 검사 순서를 한 곳에서 지킨다.
 */
@Service
public class ItemService {

    private final ItemRepository items;
    private final ItemImageRepository images;
    private final AppUserRepository users;
    private final AiPriceService ai;
    private final PreferenceService preferences;
    private final ExchangeService exchanges;
    private final RecommendationService recommendations;
    private final ModerationService moderation;
    private final TransactionTemplate tx;

    public ItemService(ItemRepository items, ItemImageRepository images, AppUserRepository users, AiPriceService ai,
                       PreferenceService preferences, ExchangeService exchanges,
                       RecommendationService recommendations, ModerationService moderation, TransactionTemplate tx) {
        this.items = items;
        this.images = images;
        this.users = users;
        this.ai = ai;
        this.preferences = preferences;
        this.exchanges = exchanges;
        this.recommendations = recommendations;
        this.moderation = moderation;
        this.tx = tx;
    }

    /**
     * 등록. 가격은 서버가 AI 분석 기록에서 가져온다.
     *  - analysisId 가 없거나, 사용자가 카테고리·상태를 바꿨으면 서버가 같은 사진으로 다시 분석한다.
     *  - AI 호출은 트랜잭션 밖에서, 저장은 트랜잭션 안에서 한다.
     */
    public ItemDtos.ItemView create(long userId, ItemDtos.CreateItemRequest input) {
        // ① 문자열 → enum (모르는 값이면 400)
        Category category = Category.parseStrict(input.category());
        ItemCondition condition = ItemCondition.parseStrict(input.condition());
        Set<Category> wishes = parseCategories(input.wishCategories());
        // 같은 사진 번호가 두 번 오면 하나로(LinkedHashSet 으로 중복 제거하되 순서 유지 → 첫 사진이 대표 사진)
        List<Long> imageIds = new ArrayList<>(new LinkedHashSet<>(input.imageIds()));

        // ② 분석 기록 확인: 내 분석인지(403), 그 사진을 등록하는지(400)
        AiAnalysis analysis = null;
        if (input.analysisId() != null) {
            analysis = ai.requireOwned(input.analysisId(), userId);
            if (!imageIds.contains(analysis.getImage().getId())) {
                throw ApiException.badRequest("ANALYSIS_IMAGE_MISMATCH", "AI가 분석한 사진이 등록할 사진에 없습니다.");
            }
        }
        // ③ 분석이 없거나, 사용자가 AI 결과와 다른 카테고리·상태를 골랐으면 그 기준으로 다시 분석(가격이 달라지므로)
        if (analysis == null || analysis.getCategory() != category || analysis.getItemCondition() != condition) {
            long imageForAi = analysis == null ? imageIds.get(0) : analysis.getImage().getId();
            analysis = ai.analyze(userId, imageForAi, new PriceSanitizer.Hint(input.title(), category, condition));
        }
        // 가격 정보 묶음(값 객체) — Item 생성자에 한 번에 넘긴다
        Item.PriceQuote quote = new Item.PriceQuote(analysis.getPrice(), analysis.getPriceMin(),
                analysis.getPriceMax(), analysis.getConfidence(), analysis.getReason(), analysis.getProvider());

        // ④ 여기부터 DB 저장 — 하나의 트랜잭션(중간에 예외가 나면 물건·사진 연결 모두 취소)
        ItemDtos.ItemView view = tx.execute(status -> {
            AppUser owner = users.findById(userId)
                    .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
            List<ItemImage> attached = new ArrayList<>();
            for (Long imageId : imageIds) {
                ItemImage image = images.findById(imageId)
                        .orElseThrow(() -> ApiException.notFound("IMAGE_NOT_FOUND", "사진을 찾을 수 없습니다: " + imageId));
                if (image.getUploader().getId() != userId) {          // 남이 올린 사진 도용 금지
                    throw ApiException.forbidden("NOT_IMAGE_OWNER", "내가 올린 사진만 쓸 수 있습니다.");
                }
                if (image.getItem() != null) {                       // 한 사진은 한 물건에만
                    throw ApiException.conflict("IMAGE_ALREADY_USED", "이미 다른 물건에 쓰인 사진입니다.");
                }
                attached.add(image);
            }
            Item item = new Item(owner, input.title(), input.description(), category, condition, quote, wishes);
            item.writeFarewell(input.farewellMessage());              // 정든 물건에게 보내는 메시지(선택, V8)
            item = items.save(item);                                  // INSERT → item.getId() 가 생긴다
            for (int i = 0; i < attached.size(); i++) {
                attached.get(i).attachTo(item, i);                    // 사진에 item_id·순서 설정(UPDATE)
                item.getImages().add(attached.get(i));                // 응답 DTO 에 사진 주소가 들어가도록 목록에도 추가
            }
            long newId = item.getId();
            // ⑤ 커밋이 끝난 뒤 콘텐츠 검사(욕설 · 선정적 · 혐오 사진, ModerationService). 통과해야 매칭 알림을 보낸다
            //    (커밋 전에 알리면 상대가 아직 없는 물건을 볼 수 있고, 보류될 물건이 남에게 알려지면 안 된다)
            AfterCommit.run(() -> moderation.reviewNewItem(newId, () -> recommendations.notifyNewItemMatches(newId)));
            return ItemDtos.ItemView.from(item);
        });
        return view;
    }

    /**
     * 상세 조회. 쓰기(조회 수 증가·선호도 학습)가 일어나므로 readOnly 가 아닌 @Transactional.
     * 내가 내 물건을 볼 때는 조회 수·학습을 하지 않는다.
     */
    @Transactional
    public ItemDtos.ItemView get(long itemId, Long viewerId) {
        Item item = require(itemId);
        // 보류(HELD) 물건은 주인만 볼 수 있다(남에게는 없는 물건처럼 404)
        if (item.getStatus() == ItemStatus.HELD && (viewerId == null || !item.isOwnedBy(viewerId))) {
            throw ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다.");
        }
        if (viewerId != null && !item.isOwnedBy(viewerId)) {
            item.increaseView();                                         // 변경 감지로 view_count UPDATE
            preferences.learn(viewerId, item.getCategory(), PreferenceService.Behavior.VIEW); // 관심 +0.03
        }
        return ItemDtos.ItemView.from(item);
    }

    /** 검색할 때 한 번에 살펴볼 최대 후보 수(교환 가능한 물건 최신순) */
    static final int SEARCH_SCAN = 1000;

    /**
     * 공개 목록(GET /api/items). PageRequest.of(0, limit) = 첫 페이지에서 limit 개만(SQL LIMIT)
     * q 가 있으면 HangulSearch 규칙(물건 이름 · 등록자 동네, 초성 검색, 여러 단어)으로 거른다.
     * 초성 · 동네 비교는 SQL LIKE 로 할 수 없어서, 교환 가능한 물건을 최신순으로 읽은 뒤 자바에서 거른다(시연 규모라 충분히 빠름).
     */
    @Transactional(readOnly = true)
    public ItemDtos.ItemList list(String category, String q, int limit) {
        Category c = category == null || category.isBlank() ? null : Category.parseStrict(category);
        String query = q == null || q.isBlank() ? null : q.trim();
        List<Item> found = query == null
                ? items.search(ItemStatus.AVAILABLE, c, null, PageRequest.of(0, limit))
                : items.search(ItemStatus.AVAILABLE, c, null, PageRequest.of(0, SEARCH_SCAN)).stream()
                        .filter(i -> HangulSearch.matches(query, i.getTitle(), i.getOwner().getRegion()))
                        .limit(limit)
                        .toList();
        return new ItemDtos.ItemList(found.stream().map(ItemDtos.ItemView::from).toList());
    }

    /** 내 물건(GET /api/me/items) — 내린(HIDDEN) 물건만 빼고 */
    @Transactional(readOnly = true)
    public ItemDtos.ItemList mine(long userId) {
        return new ItemDtos.ItemList(items.findMine(userId, ItemStatus.HIDDEN).stream()
                .map(ItemDtos.ItemView::from).toList());
    }

    /** 수정(PUT). 주인 확인 후 엔티티 메서드로 값만 바꾸면 트랜잭션 끝에 UPDATE 된다(save 호출 없음). */
    @Transactional
    public ItemDtos.ItemView update(long itemId, long userId, ItemDtos.UpdateItemRequest input) {
        Item item = requireOwned(itemId, userId);
        item.edit(input.title(), input.description(), parseCategories(input.wishCategories()));
        return ItemDtos.ItemView.from(item);
    }

    /** 내리기(DELETE) = 숨김 처리 + 걸려 있던 교환 요청 모두 취소(같은 트랜잭션) */
    @Transactional
    public void delete(long itemId, long userId) {
        Item item = requireOwned(itemId, userId);
        item.hide();
        exchanges.cancelAllFor(item, "물건이 내려가서 교환 요청이 취소되었어요.");
    }

    /** 없으면 404, 주인이 아니면 403 — 권한을 확인하기 전에는 값을 바꾸지 않는다(7장 7.4). */
    Item requireOwned(long itemId, long userId) {
        Item item = require(itemId);
        if (!item.isOwnedBy(userId)) {
            throw ApiException.forbidden("NOT_OWNER", "등록한 사용자만 변경할 수 있습니다.");
        }
        return item;
    }

    /** 물건 찾기. 내린(HIDDEN) 물건은 '없는 것'으로 본다(404). Optional.filter 로 조건에 안 맞으면 비운다. */
    Item require(long itemId) {
        return items.findById(itemId)
                .filter(i -> i.getStatus() != ItemStatus.HIDDEN)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다."));
    }

    /** ["DIGITAL","SPORTS"] → {DIGITAL, SPORTS}. null 이면 빈 집합 */
    private static Set<Category> parseCategories(List<String> raw) {
        Set<Category> set = EnumSet.noneOf(Category.class);
        if (raw != null) {
            for (String s : raw) {
                set.add(Category.parseStrict(s));
            }
        }
        return set;
    }
}
