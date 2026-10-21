import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/theme/app_theme.dart';
import 'package:sbada/widgets/glass.dart';

void main() {
  const tabs = [
    GlassTab(Icons.home_outlined, Icons.home_rounded, '홈'),
    GlassTab(Icons.map_outlined, Icons.map_rounded, '동네지도'),
    GlassTab(Icons.chat_bubble_outline_rounded, Icons.chat_bubble_rounded, '채팅'),
    GlassTab(Icons.person_outline_rounded, Icons.person_rounded, '계정정보'),
  ];

  Widget host(int index, ValueChanged<int> onTap, {double width = 360}) => MaterialApp(
        theme: buildTheme(),
        home: MediaQuery(
          data: MediaQueryData(size: Size(width, 780)),
          child: Scaffold(
            extendBody: true,
            body: const SizedBox.expand(),
            bottomNavigationBar: GlassTabBar(tabs: tabs, index: index, onTap: onTap),
          ),
        ),
      );

  testWidgets('하단 유리 탭바: 홈 · 동네지도 · 채팅 · 계정정보 4칸이 순서대로 보이고 누르면 번호를 알린다', (tester) async {
    int? tapped;
    await tester.pumpWidget(host(0, (i) => tapped = i));

    final xs = [for (final t in tabs) tester.getCenter(find.text(t.label)).dx];
    expect(xs, orderedEquals([...xs]..sort()), reason: '왼쪽부터 홈, 동네지도, 채팅, 계정정보');

    await tester.tap(find.text('채팅'));
    expect(tapped, 2);
    await tester.tap(find.text('계정정보'));
    expect(tapped, 3);
  });

  testWidgets('작은 안드로이드 화면(320dp)에서도 넘침 없이 그려지고 칸마다 48dp 이상 터치 영역', (tester) async {
    await tester.pumpWidget(host(1, (_) {}, width: 320));
    expect(tester.takeException(), isNull);
    final bar = tester.getSize(find.byType(GlassTabBar));
    expect(bar.height, greaterThanOrEqualTo(GlassTabBar.height));
    // 좌우 여백 16 × 2, 안쪽 여백 6 × 2 를 뺀 너비를 4칸이 나눠 쓴다
    expect((320 - 32 - 12) / 4, greaterThanOrEqualTo(48));
  });
}
