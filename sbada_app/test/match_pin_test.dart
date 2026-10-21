import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/map/geo.dart';
import 'package:sbada/theme/app_theme.dart';
import 'package:sbada/widgets/match_pin.dart';

void main() {
  test('매치도가 높을수록 빛이 세고, 40% 이하는 빛나지 않는다', () {
    expect(glowStrength(null), 0);
    expect(glowStrength(30), 0);
    expect(glowStrength(40), 0);
    expect(glowStrength(70), closeTo(0.5, 1e-9));
    expect(glowStrength(100), 1);
    expect(glowStrength(95), greaterThan(glowStrength(80)));
    // 높을수록 빛이 더 빨리 돈다
    expect(glowPeriod(100) < glowPeriod(60), isTrue);
  });

  test('핀 모양: 위쪽은 둥근 원, 아래는 가운데 꼭짓점으로 모인다', () {
    final path = pinPath(64, 88);
    expect(path.contains(const Offset(32, 3)), isTrue); // 원 맨 위
    expect(path.contains(const Offset(3, 32)), isTrue); // 원 왼쪽 끝
    expect(path.contains(const Offset(61, 32)), isTrue); // 원 오른쪽 끝
    expect(path.contains(const Offset(-2, 32)), isFalse); // 원 바깥
    expect(path.contains(const Offset(32, 85)), isTrue); // 꼬리 안쪽
    expect(path.contains(const Offset(4, 85)), isFalse); // 꼬리 바깥
    expect(path.contains(const Offset(32, 90)), isFalse); // 꼭짓점 아래
  });

  testWidgets('매치도 핀은 % 를 보여 주고, 점수를 모르면 – 를 보여 준다', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: buildTheme(),
      home: const Scaffold(
        body: Row(children: [
          MatchPin(imagePath: null, score: 93),
          MatchPin(imagePath: null, score: null),
        ]),
      ),
    ));
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.text('93%'), findsOneWidget);
    expect(find.text('–'), findsOneWidget);
    // 빛 여백을 포함한 크기
    expect(const MatchPin(imagePath: null, score: 50).outerSize, const Size(64 + 24, 88 + 24));
  });

  test('동네 좌표와 거리: 망원동 ↔ 합정동은 1km 안팎', () {
    final km = dongLatLng('서울 마포구 망원동').kmTo(dongLatLng('서울 마포구 합정동'));
    expect(km, inInclusiveRange(0.7, 1.6));
    // 모르는 동네도 항상 같은 자리
    expect(dongLatLng('어딘가동').lat, dongLatLng('어딘가동').lat);
  });
}
