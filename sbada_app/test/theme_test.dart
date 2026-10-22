import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/screens/profile_screen.dart';
import 'package:sbada/theme/app_theme.dart';
import 'package:shared_preferences/shared_preferences.dart';

double _luminance(Color c) {
  double ch(double v) => v <= 0.03928 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
  return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
}

double _contrast(Color a, Color b) {
  final la = _luminance(a), lb = _luminance(b);
  return (math.max(la, lb) + 0.05) / (math.min(la, lb) + 0.05);
}

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    Sb.darkMode.value = true;
  });
  tearDown(() => Sb.darkMode.value = true);

  test('모드를 바꾸면 테마 밝기와 색 토큰이 함께 바뀐다', () {
    expect(buildTheme().brightness, Brightness.dark);
    final darkBase = Sb.base;
    Sb.darkMode.value = false;
    expect(buildTheme().brightness, Brightness.light);
    expect(Sb.base, isNot(darkBase));
    expect(Sb.blue, const Color(0xFF93E1B7), reason: '포인트 색(민트)은 두 모드 공통');
  });

  test('글자 대비: 두 모드 모두 본문 4.5:1 이상, 민트 버튼 위 글자도 4.5:1 이상', () {
    for (final dark in [true, false]) {
      Sb.darkMode.value = dark;
      expect(_contrast(Sb.navy, Sb.base), greaterThanOrEqualTo(4.5), reason: '기본 글자 (dark=$dark)');
      expect(_contrast(Sb.mintText, Sb.base), greaterThanOrEqualTo(4.5), reason: '민트 글자 (dark=$dark)');
    }
    expect(_contrast(Sb.onAccent, Sb.blue), greaterThanOrEqualTo(4.5), reason: '민트 버튼 위 글자');
  });

  testWidgets('계정정보의 ☀️/🌙 스위치를 누르면 모드가 바뀌고 저장된다', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(),
      home: const Scaffold(body: Center(child: ThemeToggle())),
    ));
    await tester.tap(find.byTooltip('화이트 모드'));
    await tester.pumpAndSettle();
    expect(Sb.darkMode.value, isFalse);
    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getBool('sbada.theme.dark'), isFalse);

    await tester.tap(find.byTooltip('다크 모드'));
    await tester.pumpAndSettle();
    expect(Sb.darkMode.value, isTrue);
  });

  test('화이트 모드 계정정보: 연회색 바탕 위 흰 카드, 다크는 예전 그대로(바탕 base · 카드 card)', () {
    Sb.darkMode.value = false;
    expect(Sb.groupedBg, const Color(0xFFF2F4F6));
    expect(Sb.groupCard, const Color(0xFFFFFFFF));
    expect(_contrast(Sb.navy, Sb.groupCard), greaterThanOrEqualTo(4.5));
    expect(_contrast(Sb.text2, Sb.tabSelected), greaterThanOrEqualTo(4.5), reason: '선택 탭 글자');
    Sb.darkMode.value = true;
    expect(Sb.groupedBg, Sb.base);
    expect(Sb.groupCard, Sb.card);
  });
}
