import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/main.dart';
import 'package:sbada/network/session.dart';
import 'package:sbada/screens/login_screen.dart';
import 'package:sbada/screens/onboarding_screen.dart';
import 'package:sbada/screens/signup_screen.dart';

import 'widget_test.dart' show fakeServices;

/// 키가 낮은 화면(iPhone Duo 접은 상태 · 펼친 상태, 작은 안드로이드)에서 화면이 넘치지(overflow) 않는지
void main() {
  const sizes = {
    'Duo 접음': Size(400, 560),
    'Duo 펼침': Size(800, 560),
    '작은 폰': Size(320, 568),
  };
  for (final e in sizes.entries) {
    group(e.key, () {
      setUp(() {});
      Future<void> show(WidgetTester tester, Widget Function(dynamic s) page) async {
        tester.view.physicalSize = e.value * 3;
        tester.view.devicePixelRatio = 3;
        addTearDown(tester.view.reset);
        final services = fakeServices(MemoryStore());
        await tester.pumpWidget(SbadaApp(services: services, home: page(services)));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }

      testWidgets('온보딩 첫 화면', (t) => show(t, (s) => OnboardingScreen(services: s, mode: OnboardingMode.firstRun)));
      testWidgets('로그인 화면', (t) => show(t, (s) => LoginScreen(services: s)));
      testWidgets('회원가입 화면', (t) => show(t, (s) => SignupScreen(services: s)));
    });
  }
}
