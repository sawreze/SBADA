package com.sbada.items;

import com.sbada.common.Category;

/**
 * 물품 서버가 알려 준 물건 요약 (2026-11-25 MSA 2단계)
 * 메인 서버는 물건 DB 를 갖지 않는다. 교환 · 채팅 화면에 필요한 만큼만 배달부(gRPC GetItems)로 받아 쓴다.
 */
public record ItemRef(long id, long ownerId, String title, int price, String status, String category,
                      String categoryLabel, String imageUrl, String condition) {

    /** 물품 서버가 꺼져 있거나 물건이 사라졌을 때 화면에 대신 보여 줄 값 */
    public static ItemRef unknown(long id) {
        return new ItemRef(id, 0, "물건 정보를 불러오지 못했어요", 0, "UNKNOWN", "ETC", "기타", null, null);
    }

    public boolean isAvailable() {
        return "AVAILABLE".equals(status);
    }

    public boolean isOwnedBy(long userId) {
        return ownerId == userId;
    }

    /** 선호도 학습용 카테고리(모르면 ETC) */
    public Category categoryEnum() {
        return Category.parseLoose(category);
    }

    public ItemSummary summary() {
        return new ItemSummary(id, title, price, imageUrl, status, category, categoryLabel, ownerId);
    }

    /**
     * 교환 · 채팅 화면에 붙이는 짧은 물건 정보(모양은 예전 ItemDtos.ItemSummary 와 같다 → 앱은 그대로)
     * JSON 예: {"id":5,"title":"레고","price":140000,"imageUrl":"/api/images/5","status":"AVAILABLE",
     *          "category":"HOBBY","categoryLabel":"취미/게임/음반","ownerId":4}
     */
    public record ItemSummary(long id, String title, int price, String imageUrl, String status,
                              String category, String categoryLabel, long ownerId) {
    }
}
