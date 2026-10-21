package com.sbada.recommend;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.ApiException;
import com.sbada.common.Category;
import com.sbada.exchange.ExchangeRepository;
import com.sbada.exchange.ExchangeRequest;
import com.sbada.exchange.ExchangeStatus;
import com.sbada.item.Item;
import com.sbada.item.ItemDtos;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;
import com.sbada.user.PreferenceService;

/**
 * 스바다 추천 알고리즘 (docs/algorithm.md 에 그림과 예시 계산이 있다)
 *
 * [A] 가격 매칭 — "내 물건과 바꿀 만한 물건" (GET /api/items/{id}/matches)
 *   1단계 후보 생성: DB에서 내 물건 AI가의 0.5배~2배, 교환 가능, 남의 물건만 (인덱스 status+ai_price)
 *   2단계 점수:  0.40·가격유사도 + 0.25·내선호 + 0.20·상대선호(상호성) + 0.10·신선도 + 0.05·상태유사도
 *   3단계 다양성: 같은 카테고리가 이미 뽑힌 수만큼 0.85배씩 깎으며 위에서부터 고른다
 *
 * [B] 홈 피드 — "나를 위한 교환 추천" (GET /api/feed)
 *   0.40·선호도(온보딩+행동 학습) + 0.30·가격적합도 + 0.15·신선도 + 0.15·인기도  → 다양성 보정
 *
 * [C] 새 물건 알림 — 등록 직후, 비슷한 가격대(±30%)의 물건을 가진 사람 중
 *   이 카테고리를 원하는 사람(선호도 ≥ 0.6 또는 '바꾸고 싶은 카테고리'에 포함) 상위 5명에게 알림
 *
 * [프로그래밍 설명]
 *  - 가중합(weighted sum) 방식: 각 요소를 0~1 로 맞춘 뒤 가중치를 곱해 더한다. 가중치 합 = 1.0 → 총점도 0~1 → ×100 해서 응답.
 *  - '후보 생성 → 점수 → 재정렬' 3단계는 실제 추천 시스템의 기본 구조다. 1단계를 DB 인덱스로 300개 이하로 줄여서
 *    2단계(자바 계산)가 물건이 많아져도 빠르다.
 *  - N+1 문제 방지: 후보 주인들의 선호도를 하나씩 조회하지 않고 weightsOfUsers(ownerIds) 로 한 번에 가져온다.
 *  - @Transactional(readOnly = true): 조회 전용 → 변경 감지를 생략해 조금 더 빠르고, 실수로 고쳐도 저장되지 않는다.
 *  - static 메서드(scoreMatch, diversify, bandFit): DB 를 쓰지 않는 계산이라 따로 떼어 단위 테스트하기 쉽게 했다.
 *  - record Scored: 계산 중간 결과(물건·총점·요소·이유)를 담는 내부 전용 데이터 묶음.
 */
@Service
public class RecommendationService {

    static final double W_PRICE = 0.40;
    static final double W_WANT = 0.25;
    static final double W_MUTUAL = 0.20;
    static final double W_FRESH = 0.10;
    static final double W_COND = 0.05;

    static final double F_PREF = 0.40;
    static final double F_PRICE = 0.30;
    static final double F_FRESH = 0.15;
    static final double F_POP = 0.15;

    static final double DIVERSITY_DECAY = 0.85;
    static final int CANDIDATE_LIMIT = 300;

    private final ItemRepository items;
    private final AppUserRepository users;
    private final PreferenceService preferences;
    private final ExchangeRepository exchanges;
    private final NotificationService notifications;

    public RecommendationService(ItemRepository items, AppUserRepository users, PreferenceService preferences,
                                 ExchangeRepository exchanges, NotificationService notifications) {
        this.items = items;
        this.users = users;
        this.preferences = preferences;
        this.exchanges = exchanges;
        this.notifications = notifications;
    }

    record Scored(Item item, double total, int diffPercent, List<RecommendDtos.Factor> factors,
                  List<String> reasons) {
    }

    // ───────────────────────── [A] 가격 매칭 ─────────────────────────

    /**
     * GET /api/items/{id}/matches 의 본체.
     *  ① 기준 물건 확인(없거나 HIDDEN → 404, 남의 것 → 403)
     *  ② 가격 0.5배~2배 후보 조회(내 물건 제외, 최대 300)
     *  ③ 내 선호·키워드, 후보 주인들의 선호를 한 번에 조회
     *  ④ 이미 보낸 교환 요청 표시용 Map(물건번호 → REQUESTED)
     *  ⑤ 후보마다 scoreMatch → diversify 로 limit 개 선택 → 응답 DTO 로 변환
     */
    @Transactional(readOnly = true)
    public RecommendDtos.MatchResult matches(long itemId, long userId, int limit) {
        Item base = items.findById(itemId)
                .filter(i -> i.getStatus() != ItemStatus.HIDDEN)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다."));
        if (!base.isOwnedBy(userId)) {
            throw ApiException.forbidden("NOT_OWNER", "내 물건의 매칭만 볼 수 있습니다.");
        }
        int lo = Math.max(1, base.getPrice() / 2);
        int hi = (int) Math.min(Integer.MAX_VALUE, base.getPrice() * 2L);
        List<Item> candidates = items.findPriceBand(ItemStatus.AVAILABLE, userId, lo, hi,
                PageRequest.of(0, CANDIDATE_LIMIT));

        AppUser me = users.findById(userId).orElseThrow();
        Map<Category, Double> myPrefs = preferences.weightsOf(userId);
        Set<Long> ownerIds = new HashSet<>();
        candidates.forEach(c -> ownerIds.add(c.getOwner().getId()));
        Map<Long, Map<Category, Double>> ownerPrefs = preferences.weightsOfUsers(ownerIds);

        Map<Long, String> requested = new HashMap<>();
        for (ExchangeRequest e : exchanges.findInvolving(List.of(base.getId()), ExchangeStatus.REQUESTED)) {
            long other = e.getOfferedItem().getId().equals(base.getId()) ? e.getTargetItem().getId()
                    : e.getOfferedItem().getId();
            requested.put(other, e.getStatus().name());
        }

        Instant now = Instant.now();
        List<Scored> scored = new ArrayList<>();
        for (Item c : candidates) {
            scored.add(scoreMatch(base, c, myPrefs, me.getKeywords(),
                    ownerPrefs.getOrDefault(c.getOwner().getId(), Map.of()), now));
        }
        List<RecommendDtos.Recommendation> ranked = diversify(scored, limit).stream()
                .map(s -> toRecommendation(s, requested.get(s.item().getId()))).toList();
        return new RecommendDtos.MatchResult(ItemDtos.ItemView.from(base), "price-match-v1",
                candidates.size(), ranked);
    }

    static Scored scoreMatch(Item base, Item c, Map<Category, Double> myPrefs, Set<String> myKeywords,
                             Map<Category, Double> ownerPrefs, Instant now) {
        double price = ScoreMath.priceSimilarity(base.getPrice(), base.getPriceMin(), base.getPriceMax(),
                c.getPrice(), c.getPriceMin(), c.getPriceMax());
        double myWeight = myPrefs.getOrDefault(c.getCategory(), 0.0);
        String keyword = ScoreMath.keywordHit(c.getTitle(), myKeywords);
        double want = ScoreMath.clamp01(myWeight + (keyword != null ? 0.3 : 0.0));
        double ownerWeight = ownerPrefs.getOrDefault(base.getCategory(), 0.0);
        boolean wished = c.getWishCategories().contains(base.getCategory());
        double mutual = Math.max(ownerWeight, wished ? 1.0 : 0.0);
        double ageDays = ageDays(c, now);
        double fresh = ScoreMath.freshness(ageDays);
        double cond = ScoreMath.conditionSimilarity(base.getItemCondition().rank(), c.getItemCondition().rank());

        double total = W_PRICE * price + W_WANT * want + W_MUTUAL * mutual + W_FRESH * fresh + W_COND * cond;
        int diff = ScoreMath.diffPercent(base.getPrice(), c.getPrice());

        List<String> reasons = new ArrayList<>();
        if (Math.abs(diff) <= 20) {
            reasons.add(String.format(Locale.KOREA, "비슷한 가격대 (차이 %+d%%)", diff));
        } else {
            reasons.add(String.format(Locale.KOREA, "가격 차이 %+d%%", diff));
        }
        if (keyword != null) {
            reasons.add("관심 키워드 '" + keyword + "'");
        }
        if (myWeight >= 0.6) {
            reasons.add("내가 원하는 " + c.getCategory().label());
        }
        if (wished) {
            reasons.add("상대가 " + base.getCategory().label() + "(으)로 바꾸길 원해요");
        } else if (ownerWeight >= 0.6) {
            reasons.add("상대도 " + base.getCategory().label() + "에 관심 있어요");
        }
        if (ageDays < 2) {
            reasons.add("방금 올라온 물건");
        }
        List<RecommendDtos.Factor> factors = List.of(
                RecommendDtos.Factor.of("price", "가격 유사도", price, W_PRICE),
                RecommendDtos.Factor.of("want", "내 선호", want, W_WANT),
                RecommendDtos.Factor.of("mutual", "상대 선호(상호성)", mutual, W_MUTUAL),
                RecommendDtos.Factor.of("fresh", "신선도", fresh, W_FRESH),
                RecommendDtos.Factor.of("condition", "상태 유사도", cond, W_COND));
        return new Scored(c, total, diff, factors, reasons);
    }

    // ───────────────────────── [B] 홈 피드 ─────────────────────────

    /**
     * GET /api/feed 의 본체. 내 물건이 아닌 교환 가능 물건 전체를 후보로,
     * 선호도·가격 적합도(내 물건 가격 또는 온보딩 가격대)·신선도·인기도로 점수를 매긴다.
     * 행동 기록이 없는 새 사용자(콜드 스타트)는 선호도 0.5 로 고정 → 신선도·인기도가 순위를 정한다.
     */
    @Transactional(readOnly = true)
    public RecommendDtos.FeedResult feed(long userId, int limit) {
        AppUser me = users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
        List<Item> candidates = items.findOthers(ItemStatus.AVAILABLE, userId, PageRequest.of(0, CANDIDATE_LIMIT));
        List<Item> myItems = items.findMine(userId, ItemStatus.HIDDEN).stream()
                .filter(i -> i.getStatus() == ItemStatus.AVAILABLE).toList();
        Map<Category, Double> myPrefs = preferences.weightsOf(userId);
        boolean coldStart = myPrefs.isEmpty();
        double maxPop = candidates.stream()
                .mapToDouble(i -> ScoreMath.popularityRaw(i.getViewCount(), i.getRequestCount())).max().orElse(1);

        Set<Long> sent = new HashSet<>();
        for (ExchangeRequest e : exchanges.findSentWithStatus(userId, ExchangeStatus.REQUESTED)) {
            sent.add(e.getTargetItem().getId());
        }

        Instant now = Instant.now();
        List<Scored> scored = new ArrayList<>();
        for (Item c : candidates) {
            double w = coldStart ? 0.5 : myPrefs.getOrDefault(c.getCategory(), 0.0);
            String keyword = ScoreMath.keywordHit(c.getTitle(), me.getKeywords());
            double pref = ScoreMath.clamp01(w + (keyword != null ? 0.3 : 0.0));

            Item bestMine = null;
            double priceFit;
            if (!myItems.isEmpty()) {
                priceFit = 0;
                for (Item m : myItems) {
                    double s = ScoreMath.priceSimilarity(m.getPrice(), m.getPriceMin(), m.getPriceMax(),
                            c.getPrice(), c.getPriceMin(), c.getPriceMax());
                    if (s > priceFit) {
                        priceFit = s;
                        bestMine = m;
                    }
                }
            } else {
                priceFit = bandFit(me.getPriceMin(), me.getPriceMax(), c.getPrice());
            }
            double ageDays = ageDays(c, now);
            double fresh = ScoreMath.freshness(ageDays);
            double pop = ScoreMath.popularity(c.getViewCount(), c.getRequestCount(), maxPop);
            double total = F_PREF * pref + F_PRICE * priceFit + F_FRESH * fresh + F_POP * pop;

            List<String> reasons = new ArrayList<>();
            if (keyword != null) {
                reasons.add("관심 키워드 '" + keyword + "'");
            }
            if (!coldStart && w >= 0.6) {
                reasons.add("내가 원하는 " + c.getCategory().label());
            }
            int diff = 0;
            if (bestMine != null) {
                diff = ScoreMath.diffPercent(bestMine.getPrice(), c.getPrice());
                if (priceFit >= 0.6) {
                    reasons.add(String.format(Locale.KOREA, "내 '%s'와 비슷한 가격 (%+d%%)", bestMine.getTitle(), diff));
                }
            } else if (priceFit >= 0.99 && me.getPriceMin() != null) {
                reasons.add("내가 고른 가격대");
            }
            if (ageDays < 2) {
                reasons.add("방금 올라온 물건");
            }
            if (pop >= 0.7) {
                reasons.add("인기 물건");
            }
            List<RecommendDtos.Factor> factors = List.of(
                    RecommendDtos.Factor.of("preference", "선호도", pref, F_PREF),
                    RecommendDtos.Factor.of("price", "가격 적합도", priceFit, F_PRICE),
                    RecommendDtos.Factor.of("fresh", "신선도", fresh, F_FRESH),
                    RecommendDtos.Factor.of("popularity", "인기도", pop, F_POP));
            scored.add(new Scored(c, total, diff, factors, reasons));
        }
        List<RecommendDtos.Recommendation> ranked = diversify(scored, limit).stream()
                .map(s -> toRecommendation(s, sent.contains(s.item().getId()) ? "REQUESTED" : null)).toList();
        return new RecommendDtos.FeedResult(coldStart ? "feed-v1(cold-start)" : "feed-v1", candidates.size(), ranked);
    }

    /** 온보딩 가격대 안이면 1, 밖이면 가장 가까운 경계와의 가격 가우시안 */
    static double bandFit(Integer min, Integer max, int price) {
        if (min == null && max == null) {
            return 0.5;
        }
        int lo = min == null ? 0 : min;
        int hi = max == null ? Integer.MAX_VALUE : max;
        if (price >= lo && price <= hi) {
            return 1.0;
        }
        return price < lo ? ScoreMath.priceGauss(Math.max(1, lo), price) : ScoreMath.priceGauss(hi, price);
    }

    // ───────────────────────── 공통: 다양성 보정 ─────────────────────────

    /** 점수 순으로 고르되, 같은 카테고리가 k개 뽑혀 있으면 0.85^k 를 곱해 비교한다(MMR 간소화). */
    static List<Scored> diversify(List<Scored> scored, int limit) {
        List<Scored> pool = new ArrayList<>(scored);
        pool.sort(Comparator.comparingDouble(Scored::total).reversed());
        List<Scored> picked = new ArrayList<>();
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        while (!pool.isEmpty() && picked.size() < limit) {
            int bestIdx = 0;
            double best = -1;
            for (int i = 0; i < pool.size(); i++) {
                Scored s = pool.get(i);
                double adjusted = s.total() * Math.pow(DIVERSITY_DECAY, counts.getOrDefault(s.item().getCategory(), 0));
                if (adjusted > best) {
                    best = adjusted;
                    bestIdx = i;
                }
            }
            Scored chosen = pool.remove(bestIdx);
            counts.merge(chosen.item().getCategory(), 1, Integer::sum);
            picked.add(chosen);
        }
        return picked;
    }

    private static RecommendDtos.Recommendation toRecommendation(Scored s, String requestStatus) {
        return new RecommendDtos.Recommendation(ItemDtos.ItemView.from(s.item()),
                (int) Math.round(s.total() * 100), s.diffPercent(), s.factors(), s.reasons(), requestStatus);
    }

    private static double ageDays(Item item, Instant now) {
        return Duration.between(item.getCreatedAt(), now).toMinutes() / (60.0 * 24.0);
    }

    // ───────────────────────── [C] 새 물건 매칭 알림 ─────────────────────────

    /**
     * 등록 트랜잭션이 커밋된 뒤 별도 트랜잭션에서 실행한다.
     * REQUIRES_NEW: 바깥 트랜잭션과 상관없이 '새 트랜잭션'을 연다 → 알림 처리에서 오류가 나도 물건 등록은 이미 확정되어 있다.
     * 알림은 NotificationService 를 통해 저장 + WebSocket(접속 중) 또는 FCM 푸시(앱 꺼짐)로 전달된다.
     * 반환값 = 알림을 보낸 사람 수.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int notifyNewItemMatches(long newItemId) {
        Item fresh = items.findById(newItemId).orElse(null);
        if (fresh == null || fresh.getStatus() != ItemStatus.AVAILABLE) {
            return 0;
        }
        long ownerId = fresh.getOwner().getId();
        int lo = (int) Math.round(fresh.getPrice() * 0.77);
        int hi = (int) Math.round(fresh.getPrice() * 1.30);
        List<Item> nearby = items.findPriceBand(ItemStatus.AVAILABLE, ownerId, lo, hi, PageRequest.of(0, 200));

        Map<Long, Item> bestByOwner = new HashMap<>();
        for (Item m : nearby) {
            long o = m.getOwner().getId();
            Item cur = bestByOwner.get(o);
            if (cur == null || ScoreMath.priceGauss(m.getPrice(), fresh.getPrice())
                    > ScoreMath.priceGauss(cur.getPrice(), fresh.getPrice())) {
                bestByOwner.put(o, m);
            }
        }
        Map<Long, Map<Category, Double>> prefsByOwner = preferences.weightsOfUsers(bestByOwner.keySet());
        record Target(long userId, Item mine, double score) {
        }
        List<Target> targets = new ArrayList<>();
        for (Map.Entry<Long, Item> e : bestByOwner.entrySet()) {
            double interest = prefsByOwner.getOrDefault(e.getKey(), Map.of()).getOrDefault(fresh.getCategory(), 0.0);
            if (e.getValue().getWishCategories().contains(fresh.getCategory())) {
                interest = 1.0;
            }
            if (interest >= 0.6) {
                double score = interest * ScoreMath.priceGauss(e.getValue().getPrice(), fresh.getPrice());
                targets.add(new Target(e.getKey(), e.getValue(), score));
            }
        }
        targets.sort(Comparator.comparingDouble(Target::score).reversed());
        int sent = 0;
        for (Target t : targets.subList(0, Math.min(5, targets.size()))) {
            notifications.notify(t.userId(), NotificationType.MATCH_SUGGESTED, "바꿀 만한 물건이 올라왔어요",
                    String.format(Locale.KOREA, "내 '%s'(%,d원)와 비슷한 가격의 '%s'(%,d원) · 매칭 %d점",
                            t.mine().getTitle(), t.mine().getPrice(), fresh.getTitle(), fresh.getPrice(),
                            Math.round(t.score() * 100)),
                    "ITEM", fresh.getId());
            sent++;
        }
        return sent;
    }
}
