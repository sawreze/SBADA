import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sbada/main.dart';
import 'package:sbada/network/api_client.dart';
import 'package:sbada/network/realtime_client.dart';
import 'package:sbada/network/session.dart';
import 'package:sbada/screens/onboarding_screen.dart';
import 'package:sbada/services/app_services.dart';
import 'package:sbada/services/local_notifier.dart';

AppServices fakeServices(MemoryStore store) {
  final session = Session(store);
  final client = MockClient((req) async {
    if (req.url.path == '/api/health') {
      return http.Response.bytes(
          utf8.encode(jsonEncode({
            'status': 'UP',
            'ai': {'active': 'fallback'}
          })),
          200);
    }
    return http.Response('{}', 200);
  });
  final api = ApiClient(baseUrl: 'http://test', session: session, client: client);
  return AppServices(
    session: session,
    api: api,
    realtime: RealtimeClient(wsUrl: 'ws://test/ws', session: session),
    notifier: LocalNotifier(),
  );
}

/// 휴대폰 크기(360×800)로 그린다
void phoneSize(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

Future<void> tapVisible(WidgetTester tester, Finder finder) async {
  await tester.ensureVisible(finder);
  await tester.pumpAndSettle();
  await tester.tap(finder);
  await tester.pump();
}

void main() {
  testWidgets('처음 설치: 온보딩에서 카테고리를 1개 이상 골라야 다음으로 간다', (tester) async {
    phoneSize(tester);
    final store = MemoryStore();
    final services = fakeServices(store);
    await tester.pumpWidget(SbadaApp(
      services: services,
      home: OnboardingScreen(services: services, mode: OnboardingMode.firstRun),
    ));

    expect(find.text('시작하기'), findsOneWidget);
    await tester.tap(find.text('시작하기'));
    await tester.pumpAndSettle();

    // 아무것도 고르지 않으면 버튼이 비활성
    final disabled = tester.widget<FilledButton>(find.widgetWithText(FilledButton, '1개 이상 골라 주세요'));
    expect(disabled.onPressed, isNull);

    await tapVisible(tester, find.text('디지털기기'));
    await tapVisible(tester, find.text('취미/게임/음반'));
    expect(find.text('다음 (2개 선택)'), findsOneWidget);
  });

  testWidgets('온보딩 완료(로그인 전) → 고른 값 · 사는 동네를 기기에 보관하고 로그인 화면으로 간다', (tester) async {
    phoneSize(tester);
    final store = MemoryStore();
    final services = fakeServices(store);
    await tester.pumpWidget(SbadaApp(
      services: services,
      home: OnboardingScreen(services: services, mode: OnboardingMode.firstRun),
    ));
    await tester.tap(find.text('시작하기'));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('도서'));
    await tester.tap(find.textContaining('다음'));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('캠핑'));
    await tester.tap(find.text('다음'));
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('1~5만원'));
    await tester.tap(find.text('다음'));
    await tester.pumpAndSettle();

    // ④ 사는 동네: 비어 있으면 '완료'를 못 누르고, '해운대' 를 치면 후보가 뜬다 → 눌러서 고르기
    expect(find.text('어느 동네에\n사세요?'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, '완료')).onPressed, isNull);
    await tester.enterText(find.byKey(const ValueKey('onboarding-region')), '해운대');
    await tester.pumpAndSettle();
    await tapVisible(tester, find.text('부산 해운대구 우동'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('완료'));
    await tester.pumpAndSettle();

    expect(store.data[Session.onboardingDoneKey], 'true');
    final pending = jsonDecode(store.data[Session.pendingPrefsKey]!) as Map<String, dynamic>;
    expect(pending['wantedCategories'], ['BOOK']);
    expect(pending['keywords'], ['캠핑']);
    expect(pending['priceMin'], 10000);
    expect(pending['priceMax'], 50000);
    expect(store.data[Session.pendingRegionKey], '부산 해운대구 우동'); // 로그인 뒤 PUT /api/me/region
    expect(find.text('로그인'), findsWidgets);
  });
}
