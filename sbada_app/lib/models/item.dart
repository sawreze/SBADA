import '../util/format.dart';
import 'json.dart';
import 'user.dart';

class Item {
  const Item({
    required this.id,
    required this.title,
    this.description,
    required this.category,
    required this.categoryLabel,
    required this.categoryEmoji,
    required this.condition,
    required this.conditionLabel,
    required this.status,
    required this.price,
    required this.priceMin,
    required this.priceMax,
    required this.aiConfidence,
    this.aiReason,
    this.aiProvider,
    required this.wishCategories,
    required this.wishCategoryLabels,
    required this.imageUrls,
    required this.owner,
    required this.viewCount,
    required this.requestCount,
    this.likeCount = 0,
    required this.createdAt,
    this.moderationReason,
    this.farewellMessage,
  });

  final int id;
  final String title;
  final String? description;
  final String category;
  final String categoryLabel;
  final String categoryEmoji;
  final String condition;
  final String conditionLabel;

  /// AVAILABLE · TRADED · HIDDEN · HELD(보류: 콘텐츠 검사에 걸림, 주인만 봄)
  final String status;
  final int price;
  final int priceMin;
  final int priceMax;
  final double aiConfidence;
  final String? aiReason;
  final String? aiProvider;
  final List<String> wishCategories;
  final List<String> wishCategoryLabels;
  final List<String> imageUrls;
  final PublicUser owner;
  final int viewCount;
  final int requestCount;

  /// 찜(하트) 수 — 서버가 item_likes 를 세서 내려 준다
  final int likeCount;
  final DateTime createdAt;

  /// 보류 이유(HELD 일 때만)
  final String? moderationReason;

  /// 정든 물건에게 보내는 짧은 메시지(등록할 때 적음, 없으면 null). 목록에서 가격 아래 "…" 로 보인다
  final String? farewellMessage;

  bool get isAvailable => status == 'AVAILABLE';
  bool get isHeld => status == 'HELD';
  String? get firstImage => imageUrls.isEmpty ? null : imageUrls.first;

  factory Item.fromJson(Map<String, dynamic> j) => Item(
        id: reqInt(j, 'id'),
        title: reqStr(j, 'title'),
        description: optStr(j, 'description'),
        category: reqStr(j, 'category'),
        categoryLabel: optStr(j, 'categoryLabel') ?? '',
        categoryEmoji: optStr(j, 'categoryEmoji') ?? '',
        condition: reqStr(j, 'condition'),
        conditionLabel: optStr(j, 'conditionLabel') ?? '',
        status: reqStr(j, 'status'),
        price: reqInt(j, 'price'),
        priceMin: optInt(j, 'priceMin') ?? 0,
        priceMax: optInt(j, 'priceMax') ?? 0,
        aiConfidence: optDouble(j, 'aiConfidence'),
        aiReason: optStr(j, 'aiReason'),
        aiProvider: optStr(j, 'aiProvider'),
        wishCategories: strList(j, 'wishCategories'),
        wishCategoryLabels: strList(j, 'wishCategoryLabels'),
        imageUrls: strList(j, 'imageUrls'),
        owner: PublicUser.fromJson(j['owner'] as Map<String, dynamic>),
        viewCount: optInt(j, 'viewCount') ?? 0,
        requestCount: optInt(j, 'requestCount') ?? 0,
        likeCount: optInt(j, 'likeCount') ?? 0,
        createdAt: parseTime(j['createdAt']),
        moderationReason: optStr(j, 'moderationReason'),
        farewellMessage: optStr(j, 'farewellMessage'),
      );
}

/// 교환·채팅 카드에 쓰는 짧은 정보
class ItemSummary {
  const ItemSummary({
    required this.id,
    required this.title,
    required this.price,
    this.imageUrl,
    required this.status,
    required this.categoryLabel,
    required this.ownerId,
  });

  final int id;
  final String title;
  final int price;
  final String? imageUrl;
  final String status;
  final String categoryLabel;
  final int ownerId;

  factory ItemSummary.fromJson(Map<String, dynamic> j) => ItemSummary(
        id: reqInt(j, 'id'),
        title: reqStr(j, 'title'),
        price: reqInt(j, 'price'),
        imageUrl: optStr(j, 'imageUrl'),
        status: optStr(j, 'status') ?? 'AVAILABLE',
        categoryLabel: optStr(j, 'categoryLabel') ?? '',
        ownerId: optInt(j, 'ownerId') ?? 0,
      );
}

/// AI 상품 요약(상세 화면 '거래 희망 장소' 지도 위). provider: google · ollama · fallback(AI 없이 만든 기본 요약)
class AiItemSummary {
  const AiItemSummary({required this.summary, required this.provider, this.cached = false});

  final String summary;
  final String provider;
  final bool cached;

  bool get byAi => provider != 'fallback';

  factory AiItemSummary.fromJson(Map<String, dynamic> j) => AiItemSummary(
        summary: '${j['summary'] ?? ''}',
        provider: '${j['provider'] ?? 'fallback'}',
        cached: j['cached'] == true,
      );
}
