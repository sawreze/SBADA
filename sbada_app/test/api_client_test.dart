import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sbada/models/user.dart';
import 'package:sbada/network/api_client.dart';
import 'package:sbada/network/api_exception.dart';
import 'package:sbada/network/session.dart';
import 'package:sbada/repositories/item_repository.dart';

/// 서버 없이 MockClient 로 통신 규칙을 검사한다(10장 6절·11장).
void main() {
  late Session session;

  setUp(() async {
    session = Session(MemoryStore());
    await session.signIn('test-token',
        const AppUser(id: 1, username: 'minji', nickname: '민지', region: '망원동', onboarded: true));
  });

  test('보호 요청에는 Bearer 토큰을 붙이고, auth:false 요청에는 붙이지 않는다', () async {
    final seen = <String, String?>{};
    final client = MockClient((req) async {
      seen[req.url.path] = req.headers['Authorization'];
      return http.Response('{}', 200, headers: {'content-type': 'application/json'});
    });
    final api = ApiClient(baseUrl: 'http://test', session: session, client: client);
    await api.get('/api/me');
    await api.get('/api/health', auth: false);
    expect(seen['/api/me'], 'Bearer test-token');
    expect(seen['/api/health'], isNull);
  });

  test('목록 응답 {"items":[...]} 에서 items 를 꺼내고 한글을 UTF-8 로 읽는다', () async {
    final body = utf8.encode(jsonEncode({
      'items': [
        {
          'id': 7,
          'title': '파란 우산',
          'category': 'ETC',
          'condition': 'GOOD',
          'status': 'AVAILABLE',
          'price': 5000,
          'owner': {'id': 2, 'nickname': '준호'},
        }
      ]
    }));
    final client = MockClient((req) async => http.Response.bytes(body, 200));
    final repo = ItemRepository(ApiClient(baseUrl: 'http://test', session: session, client: client));
    final items = await repo.latest();
    expect(items.single.title, '파란 우산');
    expect(items.single.owner.nickname, '준호');
  });

  test('204 처럼 본문이 없는 성공은 null 이다(JSON 파싱 오류가 아님)', () async {
    final client = MockClient((req) async => http.Response('', 204));
    final api = ApiClient(baseUrl: 'http://test', session: session, client: client);
    expect(await api.delete('/api/items/9'), isNull);
  });

  test('오류 본문 {code,message,errors} 를 ApiException 으로 바꾼다', () async {
    final client = MockClient((req) async => http.Response.bytes(
        utf8.encode(jsonEncode({
          'code': 'VALIDATION_FAILED',
          'message': '입력값을 확인해 주세요.',
          'errors': {'title': '물건 이름을 입력해 주세요.'}
        })),
        400));
    final api = ApiClient(baseUrl: 'http://test', session: session, client: client);
    try {
      await api.post('/api/items', body: {'title': ''});
      fail('예외가 나야 한다');
    } on ApiException catch (e) {
      expect(e.status, 400);
      expect(e.code, 'VALIDATION_FAILED');
      expect(e.fieldError('title'), '물건 이름을 입력해 주세요.');
    }
  });

  test('401(만료 토큰)을 받으면 onUnauthorized 를 부른다', () async {
    var called = false;
    final client = MockClient((req) async =>
        http.Response(jsonEncode({'code': 'TOKEN_EXPIRED', 'message': 'expired'}), 401));
    final api = ApiClient(baseUrl: 'http://test', session: session, client: client)
      ..onUnauthorized = () => called = true;
    await expectLater(api.get('/api/me'), throwsA(isA<ApiException>()));
    expect(called, isTrue);
  });

  test('응답이 늦으면 보내기부터 본문 읽기까지 전체에 시간 제한이 걸린다', () async {
    final client = MockClient((req) async {
      await Future<void>.delayed(const Duration(milliseconds: 500));
      return http.Response('{}', 200);
    });
    final api = ApiClient(
        baseUrl: 'http://test', session: session, client: client, timeout: const Duration(milliseconds: 100));
    try {
      await api.get('/api/feed');
      fail('시간 초과가 나야 한다');
    } on ApiException catch (e) {
      expect(e.code, 'TIMEOUT');
      expect(e.isNetwork, isTrue);
    }
  });
}
