import 'dart:typed_data';

import '../models/ai_analysis.dart';
import '../models/item.dart';
import '../models/recommendation.dart';
import '../network/api_client.dart';
import '../network/api_exception.dart';

class ItemRepository {
  ItemRepository(this.api);

  final ApiClient api;

  // ───── 2026-11-25 사용자 요청: 물품 목록은 GraphQL 로 ─────
  // POST /graphql (메인 서버 게이트웨이 → 물품 서버). 목록 화면에 필요한 칸만 골라 받는다(설명 · AI 근거 등은 상세에서).
  // 모양은 REST ItemView 와 같아서 Item.fromJson 을 그대로 쓴다. 등록 · 수정 · 삭제 · 찜하기는 REST 그대로.
  static const _listFields = '''
    id title category categoryLabel categoryEmoji condition conditionLabel status
    price priceMin priceMax aiConfidence wishCategories wishCategoryLabels imageUrls
    owner { id nickname region } viewCount requestCount likeCount createdAt moderationReason likedByMe
  ''';

  /// GraphQL 한 번 부르기. 오류가 있으면 REST 와 같은 ApiException(코드 · 한국어 메시지)으로 바꾼다
  Future<Map<String, dynamic>> _gql(String query, [Map<String, dynamic> variables = const {}]) async {
    final res = await api.post('/graphql', body: {'query': query, 'variables': variables}) as Map<String, dynamic>;
    final errors = res['errors'];
    if (errors is List && errors.isNotEmpty) {
      final e = errors.first as Map<String, dynamic>;
      final ext = (e['extensions'] as Map?)?.cast<String, dynamic>() ?? const <String, dynamic>{};
      final code = (ext['code'] ?? ext['classification'] ?? 'GRAPHQL_ERROR').toString();
      final status = switch (ext['classification']) {
        'UNAUTHORIZED' => 401,
        'FORBIDDEN' => 403,
        'NOT_FOUND' => 404,
        _ => 400,
      };
      throw ApiException(status, code, (e['message'] ?? '요청을 처리하지 못했어요.').toString());
    }
    return (res['data'] as Map?)?.cast<String, dynamic>() ?? const <String, dynamic>{};
  }

  List<Item> _items(Object? raw) =>
      (raw as List? ?? const []).map((e) => Item.fromJson(e as Map<String, dynamic>)).toList();

  /// 나를 위한 추천(선호도·가격적합도·신선도·인기도). area: 홈 왼쪽 위 범위(util/area_scope.dart, null = 전국)
  Future<FeedResult> feed({int limit = 30, String? area}) async => FeedResult.fromJson(
      await api.get('/api/feed', query: {'limit': '$limit', 'area': ?area}) as Map<String, dynamic>);

  /// 범위 안 물건 수(GraphQL itemCount, area null = 전국)
  Future<int> count({String? area}) async {
    final data = await _gql(r'query($area: String) { itemCount(area: $area) }', {'area': area});
    return (data['itemCount'] as num).toInt();
  }

  /// 최신 목록(공개, GraphQL items). 로그인했으면 likedByMe 까지 한 번에 온다
  /// q: 물건 이름 · 동네(주소) · 초성 검색(서버 HangulSearch). limit: 검색창 추천 목록처럼 몇 개만 필요할 때
  Future<List<Item>> latest({String? category, String? q, int? limit, String? area}) async {
    final data = await _gql(
        'query(\$category: String, \$q: String, \$area: String, \$limit: Int) '
        '{ items(category: \$category, q: \$q, area: \$area, limit: \$limit) { $_listFields } }',
        {
          'category': category,
          'q': (q != null && q.isNotEmpty) ? q : null,
          'area': area,
          'limit': limit ?? 50,
        });
    return _items(data['items']);
  }

  /// 찜하기 POST /api/items/{id}/like · 해제 DELETE — 응답 {"itemId":7,"liked":true}
  Future<bool> setLiked(int itemId, bool liked) async {
    final json = (liked ? await api.post('/api/items/$itemId/like') : await api.delete('/api/items/$itemId/like'))
        as Map<String, dynamic>;
    return json['liked'] == true;
  }

  /// 내 관심목록(최근 찜한 순, GraphQL myLikes)
  Future<List<Item>> likes() async => _items((await _gql('{ myLikes { $_listFields } }'))['myLikes']);

  Future<Item> detail(int id) async => Item.fromJson(await api.get('/api/items/$id') as Map<String, dynamic>);

  /// 내 물건(GraphQL myItems, 내린 물건 제외)
  Future<List<Item>> mine() async => _items((await _gql('{ myItems { $_listFields } }'))['myItems']);

  /// 2026-11-25: 다른 사람 프로필의 물건 목록(GraphQL userItems, 공개 · 내린 물건 제외)
  Future<List<Item>> userItems(int userId, {int limit = 50}) async => _items((await _gql(
          'query(\$userId: Int!, \$limit: Int) { userItems(userId: \$userId, limit: \$limit) { $_listFields } }',
          {'userId': userId, 'limit': limit}))['userItems']);

  /// 동네지도 핀의 매치도: 보이는 물건들만 홈 추천과 같은 식으로 점수(GET /api/feed/scores?ids=). {물건 번호: 점수}
  Future<Map<int, int>> scores(List<int> ids) async {
    if (ids.isEmpty) return const {};
    final json = await api.get('/api/feed/scores', query: {'ids': ids.take(200).join(',')}) as Map<String, dynamic>;
    final raw = json['scores'] as Map<String, dynamic>;
    return {for (final e in raw.entries) int.parse(e.key): (e.value as num).toInt()};
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
