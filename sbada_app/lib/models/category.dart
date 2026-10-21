/// 서버의 Category enum 과 같은 코드. 온보딩은 로그인 전에 열리므로 앱에도 목록을 둔다.
class CategoryInfo {
  const CategoryInfo(this.code, this.label, this.emoji);

  final String code;
  final String label;
  final String emoji;

  static const all = <CategoryInfo>[
    CategoryInfo('DIGITAL', '디지털기기', '📱'),
    CategoryInfo('APPLIANCE', '생활가전', '🔌'),
    CategoryInfo('FURNITURE', '가구/인테리어', '🛋️'),
    CategoryInfo('KIDS', '유아동', '🧸'),
    CategoryInfo('FASHION', '의류', '👕'),
    CategoryInfo('ACCESSORY', '잡화', '👜'),
    CategoryInfo('BEAUTY', '뷰티/미용', '💄'),
    CategoryInfo('SPORTS', '스포츠/레저', '⚽'),
    CategoryInfo('HOBBY', '취미/게임/음반', '🎮'),
    CategoryInfo('BOOK', '도서', '📚'),
    CategoryInfo('TICKET', '티켓/교환권', '🎟️'),
    CategoryInfo('PET', '반려동물용품', '🐶'),
    CategoryInfo('PLANT', '식물', '🪴'),
    CategoryInfo('ETC', '기타', '📦'),
  ];

  static CategoryInfo of(String code) =>
      all.firstWhere((c) => c.code == code, orElse: () => all.last);
}

class ConditionInfo {
  const ConditionInfo(this.code, this.label, this.description);

  final String code;
  final String label;
  final String description;

  static const all = <ConditionInfo>[
    ConditionInfo('NEW', '새 상품', '포장도 안 뜯었어요'),
    ConditionInfo('LIKE_NEW', '거의 새것', '몇 번만 썼어요'),
    ConditionInfo('GOOD', '사용감 적음', '깨끗하게 썼어요'),
    ConditionInfo('FAIR', '사용감 많음', '흠집·얼룩이 있어요'),
    ConditionInfo('POOR', '고장/부품용', '일부 기능이 안 돼요'),
  ];

  static ConditionInfo of(String code) =>
      all.firstWhere((c) => c.code == code, orElse: () => all[2]);
}

/// 온보딩의 '주로 교환할 가격대'
class PriceBand {
  const PriceBand(this.label, this.min, this.max);

  final String label;
  final int? min;
  final int? max;

  static const all = <PriceBand>[
    PriceBand('1만원 이하', 0, 10000),
    PriceBand('1~5만원', 10000, 50000),
    PriceBand('5~20만원', 50000, 200000),
    PriceBand('20~50만원', 200000, 500000),
    PriceBand('50만원 이상', 500000, null),
    PriceBand('상관없어요', null, null),
  ];
}
