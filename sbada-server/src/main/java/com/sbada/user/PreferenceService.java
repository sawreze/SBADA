package com.sbada.user;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.ApiException;
import com.sbada.common.Category;

/**
 * 선호도 학습.
 *  ① 온보딩: 고른 카테고리 = 1.0 (명시적 선호)
 *  ② 행동: 조회 0.03, 채팅 0.10, 교환요청 0.20, 교환성사 0.30 만큼 w ← w + rate·(1−w)
 * 추천 알고리즘은 이 가중치를 '내가 원하는 정도'와 '상대가 원하는 정도'로 읽는다.
 *
 * [프로그래밍 설명]
 *  - 다른 Service(ItemService·ChatService·ExchangeService)가 사용자의 행동이 일어날 때 learn() 을 부른다.
 *    예: 물건 상세 조회 → learn(나, 그 물건의 카테고리, VIEW)
 *  - 가중치 갱신 식 w ← w + rate·(1 − w) 은 '남은 거리의 일정 비율만큼 다가가기'라서
 *    아무리 많이 반복해도 1.0 을 넘지 않고, 이미 높은 값은 조금씩만 오른다(유계 증가).
 *      예) w=0, 조회(0.03) → 0.03 / 다시 조회 → 0.0591 / 교환요청(0.2) → 0.247…
 *  - EnumMap: 키가 enum 인 Map 전용 구현(배열 기반이라 빠르고 순서가 선언 순서로 고정).
 */
@Service
public class PreferenceService {

    /** 행동 종류와 학습률(rate). enum 안에 값을 넣어 두면 '행동 → 학습률' 표가 코드 한 곳에 모인다. */
    public enum Behavior {
        VIEW(0.03), CHAT(0.10), EXCHANGE_REQUEST(0.20), TRADE(0.30);

        final double rate;

        Behavior(double rate) {
            this.rate = rate;
        }
    }

    private final AppUserRepository users;
    private final UserPreferenceRepository preferences;

    public PreferenceService(AppUserRepository users, UserPreferenceRepository preferences) {
        this.users = users;
        this.preferences = preferences;
    }

    /**
     * 온보딩 저장(PUT /api/me/preferences). 한 트랜잭션 안에서 선호도 행들과 사용자 정보를 함께 바꾼다
     * → 중간에 오류가 나면 전부 취소(원자성)되어 '일부만 저장된' 상태가 생기지 않는다.
     */
    @Transactional
    public void saveOnboarding(long userId, Set<Category> wanted, Set<String> keywords,
                               Integer priceMin, Integer priceMax) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
        if (priceMin != null && priceMax != null && priceMin > priceMax) {
            throw ApiException.badRequest("INVALID_PRICE_RANGE", "가격대의 최소값이 최대값보다 큽니다.");
        }
        // 지금 저장된 선호도를 카테고리별로 찾기 쉽게 Map 으로 바꿔 둔다
        Map<Category, UserPreference> existing = new EnumMap<>(Category.class);
        for (UserPreference p : preferences.findByUserIdOrderByWeightDesc(userId)) {
            existing.put(p.getCategory(), p);
        }
        for (Category c : Category.values()) {
            UserPreference p = existing.get(c);
            if (wanted.contains(c)) {
                if (p == null) {
                    // 처음 고른 카테고리 → 새 행 INSERT
                    preferences.save(new UserPreference(user, c, 1.0, UserPreference.Source.ONBOARDING));
                } else {
                    // 이미 있는 행 → 값만 바꾸면 변경 감지로 UPDATE (save 불필요)
                    p.resetTo(1.0, UserPreference.Source.ONBOARDING);
                }
            } else if (p != null && p.getSource() == UserPreference.Source.ONBOARDING) {
                // 예전에 골랐다가 이번에 뺀 카테고리는 약한 관심으로 남긴다
                p.resetTo(0.2, UserPreference.Source.BEHAVIOR);
            }
        }
        // 키워드 정리: 앞뒤 공백 제거, 빈 값·20자 초과 제외, 최대 10개, 중복 제거(Set)
        Set<String> cleaned = new LinkedHashSet<>();
        for (String k : keywords) {
            String t = k == null ? "" : k.trim();
            if (!t.isEmpty() && t.length() <= 20 && cleaned.size() < 10) {
                cleaned.add(t);
            }
        }
        user.completeOnboarding(cleaned, priceMin, priceMax);
    }

    /** 사용자의 행동으로 선호도를 강화한다. */
    @Transactional
    public void learn(long userId, Category category, Behavior behavior) {
        UserPreference p = preferences.findByUserIdAndCategory(userId, category).orElse(null);
        if (p == null) {
            // getReferenceById: DB 를 바로 조회하지 않고 '그 번호의 사용자'를 가리키는 프록시(대리 객체)만 만든다.
            // 외래 키(user_id)만 필요할 때 SELECT 한 번을 아낄 수 있다.
            AppUser user = users.getReferenceById(userId);
            preferences.save(new UserPreference(user, category, behavior.rate, UserPreference.Source.BEHAVIOR));
        } else {
            p.reinforce(behavior.rate);
        }
    }

    /** 한 사용자의 카테고리별 가중치 { DIGITAL=1.0, SPORTS=0.23 … } — 추천 계산에서 '내가 원하는 정도' */
    @Transactional(readOnly = true)
    public Map<Category, Double> weightsOf(long userId) {
        Map<Category, Double> map = new EnumMap<>(Category.class);
        for (UserPreference p : preferences.findByUserIdOrderByWeightDesc(userId)) {
            map.put(p.getCategory(), p.getWeight());
        }
        return map;
    }

    /**
     * 여러 사용자의 가중치를 한 번의 쿼리로 읽는다 { 사용자번호 → {카테고리 → 가중치} }.
     * 추천 후보 물건의 주인들이 '내 물건을 원하는 정도'(서로 원함 점수)를 계산할 때 쓴다.
     * 사용자마다 따로 조회하면 후보 수만큼 쿼리가 나가는 'N+1 문제'가 생기므로 IN 절로 한 번에 읽는다.
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<Category, Double>> weightsOfUsers(Collection<Long> userIds) {
        Map<Long, Map<Category, Double>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        for (UserPreference p : preferences.findByUserIds(userIds)) {
            // computeIfAbsent: 그 사용자 칸이 없으면 새 EnumMap 을 만들어 넣고, 있으면 기존 것을 돌려준다
            result.computeIfAbsent(p.getUser().getId(), k -> new EnumMap<>(Category.class))
                    .put(p.getCategory(), p.getWeight());
        }
        return result;
    }

    /** GET /api/me 응답의 preferences 목록(가중치 높은 순) */
    @Transactional(readOnly = true)
    public List<UserPreference> listOf(long userId) {
        return preferences.findByUserIdOrderByWeightDesc(userId);
    }
}
