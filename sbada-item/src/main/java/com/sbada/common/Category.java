package com.sbada.common;

import java.util.Locale;

/**
 * 물건 카테고리. basePrice 는 AI가 응답하지 못할 때 쓰는 '중고 시세 기준가'(원)다.
 *
 * [프로그래밍 설명]
 *  - enum(열거형): 정해진 값 몇 개만 가질 수 있는 타입. 문자열 "DIGITAL" 대신 Category.DIGITAL 을 쓰면
 *    오타가 컴파일 단계에서 걸러진다.
 *  - Java enum 은 클래스처럼 필드·생성자·메서드를 가질 수 있다. DIGITAL("디지털기기", "📱", 150_000) 은
 *    아래 생성자 Category(label, emoji, basePrice) 를 부른 결과 객체다.
 *  - DB 에는 @Enumerated(EnumType.STRING) 으로 이름("DIGITAL")이 저장된다(Item 엔티티 참고).
 *  - 150_000 의 밑줄은 숫자를 읽기 쉽게 하는 표기일 뿐 값은 150000 이다.
 */
public enum Category {
    DIGITAL("디지털기기", "📱", 150_000),
    APPLIANCE("생활가전", "🔌", 80_000),
    FURNITURE("가구/인테리어", "🛋️", 60_000),
    KIDS("유아동", "🧸", 25_000),
    FASHION("의류", "👕", 20_000),
    ACCESSORY("잡화", "👜", 30_000),
    BEAUTY("뷰티/미용", "💄", 15_000),
    SPORTS("스포츠/레저", "⚽", 40_000),
    HOBBY("취미/게임/음반", "🎮", 35_000),
    BOOK("도서", "📚", 8_000),
    TICKET("티켓/교환권", "🎟️", 30_000),
    PET("반려동물용품", "🐶", 20_000),
    PLANT("식물", "🪴", 15_000),
    ETC("기타", "📦", 15_000);

    private final String label;
    private final String emoji;
    private final int basePrice;

    // enum 생성자는 자동으로 private 이다(밖에서 new 로 새 카테고리를 만들 수 없다).
    Category(String label, String emoji, int basePrice) {
        this.label = label;
        this.emoji = emoji;
        this.basePrice = basePrice;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }

    public int basePrice() {
        return basePrice;
    }

    /**
     * AI가 소문자·한글 라벨로 답해도 받아들이고, 모르는 값은 ETC로 둔다.
     * '느슨한(loose)' 변환: 외부(AI) 응답처럼 형식을 믿을 수 없는 값에 쓴다. 예외를 던지지 않는다.
     */
    public static Category parseLoose(Object raw) {
        if (raw == null) {
            return ETC;
        }
        String s = raw.toString().trim();
        // 1차: "digital" / "DIGITAL" / "디지털기기" 처럼 이름이나 라벨이 정확히 같은지
        for (Category c : values()) { // values() = 모든 enum 상수를 선언 순서대로 담은 배열
            if (c.name().equalsIgnoreCase(s) || c.label.equals(s)) {
                return c;
            }
        }
        // 2차: "CATEGORY_DIGITAL" 처럼 이름이 문장 안에 들어 있는지
        String upper = s.toUpperCase(Locale.ROOT); // Locale.ROOT: 터키어 등 언어 설정에 따라 대소문자 변환이 달라지는 문제를 막는다
        for (Category c : values()) {
            if (upper.contains(c.name())) {
                return c;
            }
        }
        return ETC;
    }

    /**
     * '엄격한(strict)' 변환: 사용자가 보낸 요청 값에 쓴다. 모르는 값이면 400 오류로 거절한다.
     * Category.valueOf("XYZ") 는 IllegalArgumentException 을 던지므로 그것을 잡아 ApiException 으로 바꾼다.
     */
    public static Category parseStrict(String raw) {
        try {
            return Category.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) { // raw 가 null 이면 NullPointerException 도 여기서 잡힌다
            throw ApiException.badRequest("INVALID_CATEGORY", "알 수 없는 카테고리입니다: " + raw);
        }
    }
}
