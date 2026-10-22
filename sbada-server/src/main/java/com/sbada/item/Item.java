package com.sbada.item;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.sbada.common.ApiException;
import com.sbada.common.Category;
import com.sbada.common.ItemCondition;
import com.sbada.image.ItemImage;
import com.sbada.user.AppUser;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교환할 물건. 가격(price)은 사용자가 정하지 않고 AI 분석 결과를 서버가 복사해 둔다.
 * @Version(낙관적 잠금): 두 교환 요청이 동시에 수락되어도 한 번만 TRADED 가 되게 한다.
 *
 * 테이블 items + item_wish_categories(바꾸고 싶은 카테고리 목록) + item_images(사진, ItemImage 쪽이 외래키 보유)
 * 상태 변화:  AVAILABLE ──교환 수락──▶ TRADED
 *             AVAILABLE ──주인이 삭제──▶ HIDDEN    (TRADED 는 삭제 불가 → 409 ITEM_TRADED)
 *
 * [프로그래밍 설명 — JPA 엔티티]
 *  - @Entity: 이 클래스 객체 1개 = 테이블의 행 1개. 필드 = 열.
 *  - @Id + @GeneratedValue(IDENTITY): 기본키를 DB 자동 증가(AUTO_INCREMENT)로 만든다.
 *  - @Index: 자주 쓰는 검색 조건(상태+가격 → 가격 매칭, 카테고리, 주인)에 인덱스를 걸어 조회를 빠르게.
 *  - @Enumerated(STRING): enum 을 숫자(0,1,2)가 아닌 이름("AVAILABLE")으로 저장 → 순서를 바꿔도 데이터가 안 깨진다.
 *  - @ManyToOne(LAZY): 물건 여러 개 → 주인 1명. 주인 정보는 필요할 때만 조회.
 *  - @ElementCollection: 엔티티가 아닌 값(enum)의 목록을 별도 테이블에 저장. EAGER = 물건 조회 시 함께 가져옴.
 *  - @OneToMany(mappedBy = "item"): 관계의 주인은 ItemImage.item(외래키가 그쪽에 있음). 여기는 읽기용.
 *  - @Getter(롬복): getter 를 자동 생성. setter 는 일부러 만들지 않는다 →
 *    값은 edit(), markTraded(), hide() 같은 '의미 있는 메서드'로만 바꾸게 해서 규칙(상태 검사)을 한 곳에 모은다(도메인 모델).
 *  - @NoArgsConstructor(PROTECTED): JPA 가 객체를 만들 때 필요한 빈 생성자. 외부에서는 못 쓰게 protected.
 *  - 변경 감지(dirty checking): 트랜잭션 안에서 필드를 바꾸면 save() 없이도 커밋 때 UPDATE 가 나간다.
 *  - @Version: UPDATE 때 "WHERE version = 읽었던 값" 조건이 붙는다. 그 사이 누가 바꿨으면 0행 갱신 → 예외 → 409.
 */
@Entity
@Table(name = "items", indexes = {
        @Index(name = "idx_items_status_price", columnList = "status, ai_price"),
        @Index(name = "idx_items_category", columnList = "category"),
        @Index(name = "idx_items_owner", columnList = "owner_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ItemCondition itemCondition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ItemStatus status;

    /** AI 추정 가격(원)과 범위 */
    @Column(name = "ai_price", nullable = false)
    private int price;

    @Column(nullable = false)
    private int priceMin;

    @Column(nullable = false)
    private int priceMax;

    private double aiConfidence;

    @Column(length = 600)
    private String aiReason;

    @Column(length = 40)
    private String aiProvider;

    /** 보류(HELD) 이유. 콘텐츠 검사에 걸렸을 때만 채운다(V7) */
    @Column(name = "moderation_reason", length = 200)
    private String moderationReason;

    /** 정든 물건에게 보내는 짧은 메시지(선택, 60자). 등록할 때만 적는다(V8) */
    @Column(name = "farewell_message", length = 60)
    private String farewellMessage;

    /** 이 물건과 바꾸고 싶은 카테고리(등록자가 고름) */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "item_wish_categories", joinColumns = @JoinColumn(name = "item_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private Set<Category> wishCategories = new LinkedHashSet<>();

    @OneToMany(mappedBy = "item")
    @OrderBy("sortOrder asc, id asc")
    private List<ItemImage> images = new ArrayList<>();

    private int viewCount;
    private int requestCount;

    /** 찜(하트) 수. 테이블 열이 아니라 item_likes 를 세는 계산 값(@Formula, 읽기 전용) → 찜 표와 항상 맞고 DB 구조는 그대로 */
    @Formula("(select count(*) from item_likes l where l.item_id = id)")
    private int likeCount;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private long version;

    public Item(AppUser owner, String title, String description, Category category, ItemCondition condition,
                PriceQuote quote, Set<Category> wishCategories) {
        this.owner = owner;
        this.title = title;
        this.description = description;
        this.category = category;
        this.itemCondition = condition;
        this.status = ItemStatus.AVAILABLE;
        applyQuote(quote);
        this.wishCategories.addAll(wishCategories);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** 등록할 때 적은 '정든 물건에게 보내는 메시지'(없으면 null) */
    public void writeFarewell(String message) {
        this.farewellMessage = message;
    }

    /** 서버가 정한 AI 가격만 이 메서드로 들어온다. */
    public void applyQuote(PriceQuote quote) {
        this.price = quote.price();
        this.priceMin = quote.priceMin();
        this.priceMax = quote.priceMax();
        this.aiConfidence = quote.confidence();
        this.aiReason = quote.reason();
        this.aiProvider = quote.provider();
    }

    public void edit(String title, String description, Set<Category> wishCategories) {
        requireAvailable();
        this.title = title;
        this.description = description;
        this.wishCategories.clear();
        this.wishCategories.addAll(wishCategories);
        this.updatedAt = Instant.now();
    }

    public void markTraded() {
        requireAvailable();
        this.status = ItemStatus.TRADED;
        this.updatedAt = Instant.now();
    }

    /** 콘텐츠 검사에 걸림 → 보류. 이미 교환이 끝났거나 내린 물건은 그대로 둔다. */
    public void hold(String reason) {
        if (status != ItemStatus.AVAILABLE) {
            return;
        }
        this.status = ItemStatus.HELD;
        this.moderationReason = reason.length() > 200 ? reason.substring(0, 200) : reason;
        this.updatedAt = Instant.now();
    }

    public void hide() {
        if (status == ItemStatus.TRADED) {
            throw ApiException.conflict("ITEM_TRADED", "교환이 끝난 물건은 삭제할 수 없습니다.");
        }
        this.status = ItemStatus.HIDDEN;
        this.updatedAt = Instant.now();
    }

    public void requireAvailable() {
        if (status != ItemStatus.AVAILABLE) {
            throw ApiException.conflict("ITEM_NOT_AVAILABLE", "이미 교환되었거나 내려간 물건입니다.");
        }
    }

    public boolean isOwnedBy(long userId) {
        return owner.getId() == userId;
    }

    public void increaseView() {
        this.viewCount++;
    }

    public void increaseRequest() {
        this.requestCount++;
    }

    /** 가격 결정 결과(가격·범위·신뢰도·근거·제공자) */
    public record PriceQuote(int price, int priceMin, int priceMax, double confidence, String reason, String provider) {
    }
}
