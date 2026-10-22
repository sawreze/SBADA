package com.sbada.item;

import java.util.List;

import com.sbada.common.Category;
import com.sbada.image.ItemImage;
import com.sbada.user.UserDtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 물건 API 의 요청/응답 JSON 모양.
 *
 * [프로그래밍 설명]
 *  - record 의 '간결한 생성자(compact constructor)': public CreateItemRequest { … } 처럼 괄호 없이 쓰면
 *    필드에 값이 들어가기 직전에 실행된다. 여기서 앞뒤 공백을 정리하면 그 뒤에 @Valid 검사가 정리된 값으로 이뤄진다
 *    → "   " 처럼 공백뿐인 이름은 @NotBlank 에 걸린다.
 *  - @NotEmpty: 목록이 null 이거나 비어 있으면 실패(사진 0장 금지).
 *  - 응답에는 enum 이름(DIGITAL)과 화면용 글자(디지털기기)를 함께 넣어, 앱이 따로 번역표를 두지 않아도 되게 했다.
 */
public final class ItemDtos {

    private ItemDtos() {
    }

    /**
     * 등록 요청. 가격(price)은 받지 않는다 — 서버가 AI 분석(analysisId) 결과로 정한다.
     * 앞뒤 공백을 먼저 정리한 뒤 검증한다(7장 4.7).
     */
    public record CreateItemRequest(
            @NotBlank(message = "물건 이름을 입력해 주세요.") @Size(max = 60, message = "이름은 60자 이하입니다.")
            String title,
            @Size(max = 1000, message = "설명은 1000자 이하입니다.") String description,
            @NotBlank(message = "카테고리를 골라 주세요.") String category,
            @NotBlank(message = "상품 상태를 골라 주세요.") String condition,
            @NotEmpty(message = "사진을 1장 이상 올려 주세요.") @Size(max = 5, message = "사진은 5장까지 올릴 수 있습니다.")
            List<Long> imageIds,
            Long analysisId,
            @Size(max = 5, message = "원하는 카테고리는 5개까지 고를 수 있습니다.") List<String> wishCategories,
            // 정든 물건에게 보내는 짧은 메시지(선택, 60자). 목록에서 가격 아래 "…" 로 보인다(V8)
            @Size(max = 60, message = "메시지는 60자 이하입니다.") String farewellMessage) {
        public CreateItemRequest {
            title = title == null ? null : title.trim();
            description = description == null || description.isBlank() ? null : description.trim();
            farewellMessage = farewellMessage == null || farewellMessage.isBlank() ? null : farewellMessage.trim();
        }
    }

    /** 수정 요청(PUT). 이름·설명·원하는 카테고리만 바꿀 수 있다. */
    public record UpdateItemRequest(
            @NotBlank(message = "물건 이름을 입력해 주세요.") @Size(max = 60, message = "이름은 60자 이하입니다.")
            String title,
            @Size(max = 1000, message = "설명은 1000자 이하입니다.") String description,
            @Size(max = 5, message = "원하는 카테고리는 5개까지 고를 수 있습니다.") List<String> wishCategories) {
        public UpdateItemRequest {
            title = title == null ? null : title.trim();
            description = description == null || description.isBlank() ? null : description.trim();
        }
    }

    /** 물건 하나의 전체 정보(목록·상세·등록·수정 응답). JSON 예시는 ItemController.get 주석 참고. */
    public record ItemView(long id, String title, String description,
                           String category, String categoryLabel, String categoryEmoji,
                           String condition, String conditionLabel, String status,
                           int price, int priceMin, int priceMax, double aiConfidence, String aiReason,
                           String aiProvider, List<String> wishCategories, List<String> wishCategoryLabels,
                           List<String> imageUrls, UserDtos.PublicUser owner,
                           int viewCount, int requestCount, int likeCount, String createdAt, String moderationReason,
                           String farewellMessage) {
        public static ItemView from(Item i) {
            // 원하는 카테고리는 enum 선언 순서로 정렬해 항상 같은 순서로 보낸다
            List<Category> wishes = i.getWishCategories().stream().sorted().toList();
            return new ItemView(i.getId(), i.getTitle(), i.getDescription(),
                    i.getCategory().name(), i.getCategory().label(), i.getCategory().emoji(),
                    i.getItemCondition().name(), i.getItemCondition().label(), i.getStatus().name(),
                    i.getPrice(), i.getPriceMin(), i.getPriceMax(),
                    Math.round(i.getAiConfidence() * 100) / 100.0, i.getAiReason(), i.getAiProvider(),
                    // Enum::name, Category::label, ItemImage::url = 메서드 참조(각 원소에 그 메서드를 적용)
                    wishes.stream().map(Enum::name).toList(), wishes.stream().map(Category::label).toList(),
                    i.getImages().stream().map(ItemImage::url).toList(),
                    UserDtos.PublicUser.from(i.getOwner()),
                    // Instant.toString() = ISO-8601 형식 "2026-10-21T02:00:00Z" (앱이 DateTime.parse 로 읽는다)
                    i.getViewCount(), i.getRequestCount(), i.getLikeCount(), i.getCreatedAt().toString(),
                    // 보류(HELD) 이유: 주인에게만 보이는 물건이라 그대로 내려 준다(그 밖의 상태는 null)
                    i.getModerationReason(),
                    i.getFarewellMessage());
        }
    }

    /**
     * 교환·채팅 화면에 붙이는 짧은 물건 정보
     * JSON 예: {"id":5,"title":"레고","price":140000,"imageUrl":"/api/images/5","status":"AVAILABLE",
     *          "category":"HOBBY","categoryLabel":"취미/게임/음반","ownerId":4}
     */
    public record ItemSummary(long id, String title, int price, String imageUrl, String status,
                              String category, String categoryLabel, long ownerId) {
        public static ItemSummary from(Item i) {
            String image = i.getImages().isEmpty() ? null : i.getImages().get(0).url(); // 대표 사진(첫 번째)
            return new ItemSummary(i.getId(), i.getTitle(), i.getPrice(), image, i.getStatus().name(),
                    i.getCategory().name(), i.getCategory().label(), i.getOwner().getId());
        }
    }

    /** 목록 응답 {"items": [...]} */
    public record ItemList(List<ItemView> items) {
    }

    /** GET /api/categories 의 카테고리 한 줄 */
    public record CategoryView(String code, String label, String emoji, int basePrice) {
    }
}
