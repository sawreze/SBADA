import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/main.dart';
import 'package:sbada/network/session.dart';
import 'package:sbada/screens/onboarding_screen.dart';
import 'package:sbada/screens/splash_gate.dart';
import 'package:sbada/theme/app_theme.dart';

import 'widget_test.dart' show fakeServices, phoneSize;

void main() {
  testWidgets('실행 화면: 아이콘 배경색 위 가운데 아이콘 + "쓰던 물건을 바꾸다." 를 2.5초 보여 준 뒤 다음 화면으로 간다', (tester) async {
    phoneSize(tester);
    final services = fakeServices(MemoryStore()); // 처음 설치(로그인 전) → 다음은 온보딩
    await tester.pumpWidget(SbadaApp(services: services));
    await tester.pump();

    final scaffold = tester.widget<Scaffold>(find.byType(Scaffold).first);
    expect(scaffold.backgroundColor, Sb.splashBg);
    expect(find.text('쓰던 물건을 바꾸다.'), findsOneWidget);
    final logo = find.byType(SwapLogo);
    for (final asset in ['assets/splash_left.png', 'assets/splash_right.png']) {
      expect(find.byWidgetPredicate((w) => w is Image && w.image is AssetImage && (w.image as AssetImage).assetName == asset),
          findsOneWidget);
    }
    expect(logo, findsOneWidget);
    // 아이콘은 화면 정가운데
    expect((tester.getCenter(logo) - tester.getCenter(find.byType(SplashGate))).distance, lessThan(0.5));

    // 곰(왼쪽)과 컵(오른쪽)은 서로 반대로 위아래로 움직인다: 1/4 바퀴(0.6초) 뒤 왼쪽은 위(-), 오른쪽은 아래(+)
    await tester.pump(const Duration(milliseconds: 600));
    final moves = tester
        .widgetList<Transform>(find.descendant(of: logo, matching: find.byType(Transform)))
        .map((t) => t.transform.getTranslation().y)
        .where((y) => y.abs() > 0.5)
        .toList();
    expect(moves, hasLength(2));
    expect(moves[0], lessThan(0)); // 왼쪽 올라감
    expect(moves[1], greaterThan(0)); // 오른쪽 내려감
    expect((moves[0] + moves[1]).abs(), lessThan(0.01)); // 같은 높이만큼

    // 2.5초 전에는 그대로
    await tester.pump(const Duration(milliseconds: 1700));
    expect(find.byType(OnboardingScreen), findsNothing);

    // 2.5초가 지나면 온보딩으로
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(find.byType(OnboardingScreen), findsOneWidget);
  });
}
