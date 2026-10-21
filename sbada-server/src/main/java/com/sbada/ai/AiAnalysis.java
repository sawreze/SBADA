package com.sbada.ai;

import java.time.Instant;

import com.sbada.common.Category;
import com.sbada.common.ItemCondition;
import com.sbada.image.ItemImage;
import com.sbada.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 가격 분석 기록. 상품 등록 때 서버가 이 기록의 가격을 복사한다(앱이 보낸 가격은 믿지 않는다).
 *
 * 테이블 ai_analyses: 분석 1회 = 1행. 누가(user_id) 어떤 사진(image_id)을 분석했고,
 * 모델 원래 가격(raw_price) → 보정 후 최종가(final_price), 사용 모델·걸린 시간, 시세 보정 정보를 남긴다.
 *
 * [프로그래밍 설명]
 *  - 보안 설계: 앱은 POST /api/items 때 가격 대신 analysisId 만 보낸다. 서버가 이 행에서 가격을 꺼내
 *    Item 에 복사하므로, 사용자가 요청을 조작해 가격을 바꿀 수 없다.
 *  - @ManyToOne(fetch = LAZY): 사용자·사진 정보는 실제로 꺼낼 때만 조회(불필요한 JOIN 방지).
 *  - @Column(name = "final_price") private int price: 자바 필드 이름과 DB 열 이름을 다르게 연결할 수 있다.
 *  - tags 는 List 대신 "a,b,c" 문자열 하나로 저장(따로 테이블을 만들 만큼 중요하지 않아서).
 */
@Entity
@Table(name = "ai_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id")
    private ItemImage image;

    @Column(length = 60)
    private String suggestedTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ItemCondition itemCondition;

    /** 모델이 처음 낸 가격(보정 전) */
    private int rawPrice;

    @Column(name = "final_price", nullable = false)
    private int price;

    private int priceMin;
    private int priceMax;
    private double confidence;

    @Column(length = 600)
    private String reason;

    @Column(length = 200)
    private String tags;

    @Column(length = 20)
    private String provider;

    @Column(length = 60)
    private String model;

    private long latencyMs;

    /** 앱 내 시세 보정 정보 */
    private int marketSamples;
    private Integer marketMedian;
    private double marketWeight;

    @Column(nullable = false)
    private Instant createdAt;

    public AiAnalysis(AppUser user, ItemImage image, PriceSanitizer.Result r, int rawPrice,
                      PriceSanitizer.MarketBlend blend, String provider, String model, long latencyMs) {
        this.user = user;
        this.image = image;
        this.suggestedTitle = r.title();
        this.category = r.category();
        this.itemCondition = r.condition();
        this.rawPrice = rawPrice;
        this.price = blend.price();
        this.priceMin = blend.priceMin();
        this.priceMax = blend.priceMax();
        this.confidence = r.confidence();
        this.reason = blend.reason();
        this.tags = String.join(",", r.tags());
        this.provider = provider;
        this.model = model;
        this.latencyMs = latencyMs;
        this.marketSamples = blend.samples();
        this.marketMedian = blend.median();
        this.marketWeight = blend.weight();
        this.createdAt = Instant.now();
    }
}
