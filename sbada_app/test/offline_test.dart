import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/network/api_exception.dart';
import 'package:sbada/theme/app_theme.dart';
import 'package:sbada/widgets/offline_view.dart';

void main() {
  // testWidgets 안에서는 시간이 가짜라 tester.pump(시간)으로 15초를 바로 넘길 수 있다
  testWidgets('연결 실패(바로 나는 오류)는 15초가 지나야 오류로 넘어온다', (tester) async {
    Object? error;
    holdNetworkError(Future<int>.error(ApiException.network())).then((_) {}, onError: (Object e) {
      error = e;
    });
    await tester.pump(const Duration(seconds: 14));
    expect(error, isNull, reason: '15초 전에는 계속 불러오는 중');
    await tester.pump(const Duration(seconds: 1));
    expect(error, isA<ApiException>().having((e) => e.isNetwork, 'isNetwork', true));
  });

  testWidgets('인터넷이 약해 15초 제한(TIMEOUT)에 걸리면 더 기다리지 않고 바로 넘긴다', (tester) async {
    Object? error;
    final slow = Future<int>.delayed(const Duration(seconds: 15), () => throw ApiException.timeout());
    holdNetworkError(slow).then((_) {}, onError: (Object e) {
      error = e;
    });
    await tester.pump(const Duration(seconds: 15));
    await tester.pump();
    expect(error, isA<ApiException>().having((e) => e.code, 'code', 'TIMEOUT'));
  });

  testWidgets('연결 오류가 아닌 것(404)과 성공은 기다리지 않고, 타이머도 남기지 않는다', (tester) async {
    Object? error;
    int? value;
    holdNetworkError(Future<int>.error(ApiException(404, 'NOT_FOUND', '없어요')))
        .then((_) {}, onError: (Object e) {
      error = e;
    });
    holdNetworkError(Future.value(7)).then((v) => value = v);
    await tester.pump();
    expect(error, isA<ApiException>().having((e) => e.status, 'status', 404));
    expect(value, 7);
    // 타이머가 남아 있으면 testWidgets 가 끝날 때 'Timer is still pending' 으로 실패한다
  });

  testWidgets('OfflineView: 가운데 문구 + 아래 우는 얼굴(그림 없으면 😭) + 다시 불러오기', (tester) async {
    var retried = 0;
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(),
      home: Scaffold(body: OfflineView(onRetry: () => retried++)),
    ));
    await tester.pumpAndSettle();

    final title = find.text('화면을 불러오지 못했어요');
    expect(title, findsOneWidget);
    final face = find.byKey(const ValueKey('offline-emoji'));
    expect(face, findsOneWidget, reason: 'assets/offline/offline.png 가 없으면 이모지');
    expect(tester.getCenter(face).dy, greaterThan(tester.getCenter(title).dy), reason: '그림은 문구 아래');
    expect(tester.getCenter(title).dx, closeTo(400, 1), reason: '가로 가운데(테스트 화면 폭 800)');

    await tester.tap(find.text('다시 불러오기'));
    expect(retried, 1);
  });
}
