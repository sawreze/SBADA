import 'dart:typed_data';

import '../models/ai_analysis.dart';
import '../models/item.dart';
import '../models/recommendation.dart';
import '../network/api_client.dart';

class ItemRepository {
  ItemRepository(this.api);

  final ApiClient api;

  /// 나를 위한 추천(선호도·가격적합도·신선도·인기도). area: 홈 왼쪽 위 범위(util/area_scope.dart, null = 전국)
  Future<FeedResult> feed({int limit = 30, String? area}) async => FeedResult.fromJson(
      await api.get('/api/feed', query: {'limit': '$limit', 'area': ?area}) as Map<String, dynamic>);

  /// 범위 안 물건 수 GET /api/items/count (area null = 전국)
  Future<int> count({String? area}) async {
    final json = await api.get('/api/items/count', query: {'area': ?area}, auth: false) as Map<String, dynamic>;
    return (json['count'] as num).toInt();
  }

  /// 최신 목록(공개). 목록 응답은 {"items": [...]} 이므로 items 를 꺼낸다(10장 3.1)
  /// q: 물건 이름 · 동네(주소) · 초성 검색(서버 HangulSearch). limit: 검색창 추천 목록처럼 몇 개만 필요할 때
  Future<List<Item>> latest({String? category, String? q, int? limit, String? area}) async {
    final json = await api.get('/api/items', query: {
      'category': ?category,
      'area': ?area,
      if (q != null && q.isNotEmpty) 'q': q,
      if (limit != null) 'limit': '$limit',
    }) as Map<String, dynamic>;
    return (json['items'] as List).map((e) => Item.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// 찜하기 POST /api/items/{id}/like · 해제 DELETE — 응답 {"itemId":7,"liked":true}
  Future<bool> setLiked(int itemId, bool liked) async {
    final json = (liked ? await api.post('/api/items/$itemId/like') : await api.delete('/api/items/$itemId/like'))
        as Map<String, dynamic>;
    return json['liked'] == true;
  }

  /// 내 관심목록(최근 찜한 순) GET /api/me/likes
  Future<List<Item>> likes() async {
    final json = await api.get('/api/me/likes') as Map<String, dynamic>;
    return (json['items'] as List).map((e) => Item.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<Item> detail(int id) async => Item.fromJson(await api.get('/api/items/$id') as Map<String, dynamic>);

  Future<List<Item>> mine() async {
    final json = await api.get('/api/me/items') as Map<String, dynamic>;
    return (json['items'] as List).map((e) => Item.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// AI 상품 요약(GET /api/items/{id}/summary). 처음 한 번은 Gemma 가 쓰느라 30초 안팎 → 100초까지 기다린다
  Future<AiItemSummary> summary(int itemId) async => AiItemSummary.fromJson(
      await api.get('/api/items/$itemId/summary', timeout: const Duration(seconds: 100)) as Map<String, dynamic>);

  /// 내 물건과 비슷한 가격대의 교환 후보
  Future<MatchResult> matches(int itemId, {int limit = 20}) async => MatchResult.fromJson(
      await api.get('/api/items/$itemId/matches', query: {'limit': '$limit'}) as Map<String, dynamic>);

  /// 사진 업로드 → 서버가 준 사진 번호
  Future<int> uploadImage(Uint8List bytes, String filename) async {
    final json = await api.uploadImage(bytes, filename);
    return (json['id'] as num).toInt();
  }

  /// AI 가격 분석. category·condition 을 주면 그 기준으로 다시 분석한다.
  Future<AiAnalysis> analyze(int imageId, {String? title, String? category, String? condition}) async {
    final json = await api.post('/api/ai/analyze',
        timeout: const Duration(seconds: 100),
        body: {
          'imageId': imageId,
          'title': title,
          'category': category,
          'condition': condition,
        }) as Map<String, dynamic>;
    return AiAnalysis.fromJson(json);
  }

  /// 등록. 가격은 보내지 않는다 — 서버가 analysisId 의 결과로 정한다.
  Future<Item> create({
    required String title,
    String? description,
    required String category,
    required String condition,
    required List<int> imageIds,
    int? analysisId,
    List<String> wishCategories = const [],
    String? farewellMessage,
  }) async {
    final json = await api.post('/api/items',
        timeout: const Duration(seconds: 100),
        body: {
          'title': title,
          'description': description,
          'category': category,
          'condition': condition,
          'imageIds': imageIds,
          'analysisId': analysisId,
          'wishCategories': wishCategories,
          'farewellMessage': farewellMessage,
        }) as Map<String, dynamic>;
    return Item.fromJson(json);
  }

  Future<void> delete(int id) async => api.delete('/api/items/$id');

  Future<Map<String, dynamic>> aiStatus() async =>
      await api.get('/api/ai/status', auth: false) as Map<String, dynamic>;
}
