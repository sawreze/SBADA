import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/models/ai_analysis.dart';
import 'package:sbada/models/exchange.dart';
import 'package:sbada/models/item.dart';
import 'package:sbada/models/recommendation.dart';
import 'package:sbada/util/format.dart';

/// 서버 응답 표본(실제 API 모양)을 모델로 읽을 수 있는지 확인한다(10장 3.3).
Map<String, dynamic> itemJson({int id = 7, String? description = '설명'}) => {
      'id': id,
      'title': '에어팟 프로 2세대',
      'description': description,
      'category': 'DIGITAL',
      'categoryLabel': '디지털기기',
      'categoryEmoji': '📱',
      'condition': 'GOOD',
      'conditionLabel': '사용감 적음',
      'status': 'AVAILABLE',
      'price': 150000,
      'priceMin': 125000,
      'priceMax': 175000,
      'aiConfidence': 0.82,
      'aiReason': '시세 반영',
      'aiProvider': 'google',
      'wishCategories': ['HOBBY'],
      'wishCategoryLabels': ['취미/게임/음반'],
      'imageUrls': ['/api/images/3'],
      'owner': {'id': 1, 'nickname': '민지', 'region': '망원동'},
      'viewCount': 27,
      'requestCount': 1,
      'createdAt': '2026-10-20T08:00:00Z',
    };

void main() {
  test('Item: 정상 응답과 허용된 null(설명 없음)을 읽는다', () {
    final item = Item.fromJson(itemJson(description: null));
    expect(item.id, 7);
    expect(item.price, 150000);
    expect(item.description, isNull);
    expect(item.owner.nickname, '민지');
    expect(item.isAvailable, isTrue);
    expect(item.firstImage, '/api/images/3');
  });

  test('Item: 필수 값이 빠지거나 타입이 다르면 FormatException(기본값으로 숨기지 않는다)', () {
    final missing = itemJson()..remove('title');
    expect(() => Item.fromJson(missing), throwsFormatException);
    final wrongType = itemJson()..['price'] = '십오만원';
    expect(() => Item.fromJson(wrongType), throwsFormatException);
  });

  test('Recommendation: 점수와 구성 요소를 읽는다', () {
    final rec = Recommendation.fromJson({
      'item': itemJson(),
      'score': 83,
      'priceDiffPercent': -7,
      'factors': [
        {'key': 'price', 'label': '가격 유사도', 'value': 0.95, 'weight': 0.4},
        {'key': 'want', 'label': '내 선호', 'value': 1.0, 'weight': 0.25},
      ],
      'reasons': ['비슷한 가격대 (차이 -7%)'],
      'requestStatus': null,
    });
    expect(rec.score, 83);
    expect(rec.factors.first.contribution, closeTo(0.38, 1e-9));
    expect(rec.requestStatus, isNull);
  });

  test('AiAnalysis: 제공자 표시', () {
    final a = AiAnalysis.fromJson({
      'analysisId': 1,
      'imageId': 3,
      'suggestedTitle': '닌텐도 스위치',
      'category': 'HOBBY',
      'condition': 'LIKE_NEW',
      'price': 280000,
      'priceMin': 240000,
      'priceMax': 320000,
      'confidence': 0.86,
      'reason': 'OLED 시세',
      'tags': ['닌텐도'],
      'provider': 'ollama',
      'model': 'gemma4:e4b',
      'latencyMs': 4210,
      'marketSamples': 0,
      'marketMedian': null,
      'marketWeight': 0.0,
    });
    expect(a.isAi, isTrue);
    expect(a.providerLabel, contains('Gemma'));
  });

  test('Exchange: 받은 사람/보낸 사람 판단', () {
    final summary = {'id': 7, 'title': '레고', 'price': 140000, 'status': 'AVAILABLE', 'ownerId': 2};
    final ex = Exchange.fromJson({
      'id': 5,
      'status': 'REQUESTED',
      'requester': {'id': 2, 'nickname': '도현', 'region': ''},
      'targetOwner': {'id': 1, 'nickname': '민지', 'region': ''},
      'offeredItem': summary,
      'targetItem': {...summary, 'id': 8, 'title': '에어팟', 'price': 150000, 'ownerId': 1},
      'priceDiffPercent': -7,
      'createdAt': '2026-10-20T08:00:00Z',
    });
    expect(ex.isReceiver(1), isTrue);
    expect(ex.isRequester(2), isTrue);
    expect(ex.statusLabel, '수락 대기');
  });

  test('금액·시간 표시', () {
    expect(formatWon(128000), '128,000원');
    expect(formatWon(1234567), '1,234,567원');
    expect(formatWon(500), '500원');
    expect(formatShortWon(280000), '28만원');
    expect(formatShortWon(12000), '1.2만원');
    expect(formatPercent(8), '+8%');
    expect(formatPercent(-12), '-12%');
    final now = DateTime(2026, 10, 20, 12);
    expect(timeAgo(now.subtract(const Duration(minutes: 5)), now: now), '5분 전');
    expect(timeAgo(now.subtract(const Duration(hours: 3)), now: now), '3시간 전');
  });
}
