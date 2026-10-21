package com.sbada.image;

import java.time.Instant;

import com.sbada.item.Item;
import com.sbada.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 사진 메타데이터. 실제 바이트는 data/uploads/{storedName} 에 두고,
 * DB에는 이름·형식·크기만 둔다(7장 9.1 파일 바이트와 메타데이터 분리).
 * 상품 등록 전에 먼저 올리므로 item 은 처음에 비어 있다.
 *
 * [프로그래밍 설명]
 *  - 연관 관계 두 개:
 *      uploader (@ManyToOne, 필수) : 여러 사진 → 올린 사람 1명  (외래 키 uploader_id)
 *      item     (@ManyToOne, 선택) : 여러 사진 → 물건 1개       (외래 키 item_id, 등록 전에는 null)
 *  - 사진의 생명주기: 업로드(item=null) → AI 분석 → 물건 등록 시 attachTo(item, 순서) 로 물건에 붙는다.
 */
@Entity
@Table(name = "item_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id")
    private AppUser uploader;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    /** 디스크에 저장된 파일 이름(UUID.jpg) */
    @Column(nullable = false, unique = true, length = 60)
    private String storedName;

    /** 사용자가 올린 원래 파일 이름(참고용) */
    @Column(length = 120)
    private String originalName;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false)
    private long size;

    private int width;
    private int height;

    /** 사진 순서(0 = 대표 사진) */
    private int sortOrder;

    @Column(nullable = false)
    private Instant createdAt;

    public ItemImage(AppUser uploader, String storedName, String originalName, String contentType,
                     long size, int width, int height) {
        this.uploader = uploader;
        this.storedName = storedName;
        this.originalName = originalName;
        this.contentType = contentType;
        this.size = size;
        this.width = width;
        this.height = height;
        this.createdAt = Instant.now();
    }

    /** 물건 등록 때 이 사진을 물건에 붙인다(트랜잭션 안이라 변경 감지로 item_id·sort_order 가 UPDATE 된다) */
    public void attachTo(Item item, int sortOrder) {
        this.item = item;
        this.sortOrder = sortOrder;
    }

    /** API 응답에 넣는 사진 주소. 앱은 서버 주소를 앞에 붙여(http://10.0.2.2:8097/api/images/33) 사진을 받는다. */
    public String url() {
        return "/api/images/" + id;
    }
}
