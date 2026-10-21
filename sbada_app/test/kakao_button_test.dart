import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/screens/login_screen.dart';

void main() {
  testWidgets('카카오로 시작하기 버튼: 노란 배경 · 문구 · 누르면 콜백, 로딩 중엔 눌리지 않는다', (tester) async {
    var taps = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: KakaoStartButton(loading: false, onPressed: () => taps++)),
    ));
    expect(find.text('카카오로 시작하기'), findsOneWidget);
    final button = tester.widget<FilledButton>(find.byKey(const ValueKey('kakao-login')));
    expect(button.style!.backgroundColor!.resolve({}), const Color(0xFFFEE500));
    await tester.tap(find.text('카카오로 시작하기'));
    expect(taps, 1);

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(body: KakaoStartButton(loading: true, onPressed: () => taps++)),
    ));
    expect(find.text('카카오로 시작하기'), findsNothing);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('kakao-login')));
    expect(taps, 1);
  });
}
