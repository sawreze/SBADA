import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/util/apple.dart';
import 'package:sbada/widgets/expand_route.dart';
import 'package:sbada/widgets/glass.dart';

/// iOS 전용 디자인(리퀴드 글래스 · 젤리 탭 · 펼쳐지는 화면 전환)이 오류 없이 동작하는지
void main() {
  setUp(() => Apple.debugOverride = true);
  tearDown(() => Apple.debugOverride = null);

  testWidgets('iOS 탭바: 젤리 알약이 움직이고 끝나면 새 칸에 선다', (tester) async {
    var index = 0;
    await tester.pumpWidget(MaterialApp(
      home: StatefulBuilder(
        builder: (context, setState) => Scaffold(
          bottomNavigationBar: GlassTabBar(
            tabs: const [
              GlassTab(Icons.home_outlined, Icons.home, '홈'),
              GlassTab(Icons.map_outlined, Icons.map, '지도'),
              GlassTab(Icons.chat_outlined, Icons.chat, '채팅'),
              GlassTab(Icons.person_outline, Icons.person, '나'),
            ],
            index: index,
            onTap: (i) => setState(() => index = i),
          ),
        ),
      ),
    ));
    final x0 = tester.getTopLeft(find.byKey(const ValueKey('tab-selected-pill'))).dx;
    await tester.tap(find.text('채팅'));
    await tester.pump(const Duration(milliseconds: 120));
    await tester.pumpAndSettle();
    final x1 = tester.getTopLeft(find.byKey(const ValueKey('tab-selected-pill'))).dx;
    expect(index, 2);
    expect(x1, greaterThan(x0));
    expect(tester.takeException(), isNull);
  });

  testWidgets('iOS 탭바: 손가락으로 렌즈를 끌어다 놓으면 가장 가까운 칸이 열린다', (tester) async {
    var index = 0;
    await tester.pumpWidget(MaterialApp(
      home: StatefulBuilder(
        builder: (context, setState) => Scaffold(
          bottomNavigationBar: GlassTabBar(
            tabs: const [
              GlassTab(Icons.home_outlined, Icons.home, '홈'),
              GlassTab(Icons.map_outlined, Icons.map, '지도'),
              GlassTab(Icons.chat_outlined, Icons.chat, '채팅'),
              GlassTab(Icons.person_outline, Icons.person, '나'),
            ],
            index: index,
            onTap: (i) => setState(() => index = i),
          ),
        ),
      ),
    ));
    final start = tester.getCenter(find.text('홈'));
    final end = tester.getCenter(find.text('채팅'));
    final g = await tester.startGesture(start);
    await g.moveBy(const Offset(30, 0));
    await tester.pump(const Duration(milliseconds: 100));
    await g.moveTo(end);
    await tester.pump(const Duration(milliseconds: 100));
    // 끄는 동안 렌즈(확대경)가 떠 있다
    expect(find.byType(RawMagnifier), findsOneWidget);
    await g.up();
    // 2026-11-25: 놓는 순간 렌즈가 바로 사라지고(딜레이 없음), 0.2초 안에 알약이 새 칸에 붙는다
    await tester.pump();
    expect(find.byType(RawMagnifier), findsNothing);
    await tester.pump(const Duration(milliseconds: 200));
    final settledEarly = tester.getTopLeft(find.byKey(const ValueKey('tab-selected-pill'))).dx;
    await tester.pumpAndSettle();
    expect(index, 2);
    expect(find.byType(RawMagnifier), findsNothing);
    expect(tester.getTopLeft(find.byKey(const ValueKey('tab-selected-pill'))).dx, closeTo(settledEarly, 0.5));
    expect(tester.takeException(), isNull);
  });

  testWidgets('iOS: 목록 칸을 누르면 그 자리에서 화면이 퍼지고, 뒤로 가면 다시 줄어든다', (tester) async {
    await tester.pumpWidget(MaterialApp(
      builder: (context, child) =>
          Listener(onPointerDown: (e) => ExpandOrigin.capture(e.position), child: child),
      home: Builder(
        builder: (context) => Scaffold(
          body: ListView(children: [
            for (var i = 0; i < 5; i++)
              ExpandSource(
                child: PressScale(
                  child: ListTile(
                    minTileHeight: 80,
                    title: Text('물건 $i'),
                    onTap: () => Navigator.of(context).push(appRoute<void>(
                      builder: (_) => Scaffold(appBar: AppBar(), body: Text('상세 $i')),
                    )),
                  ),
                ),
              ),
          ]),
        ),
      ),
    ));
    await tester.tap(find.text('물건 2'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
    // 중간: 상세 화면이 아직 전체 화면보다 작다(퍼지는 중)
    expect(find.byType(ClipRRect), findsWidgets);
    await tester.pumpAndSettle();
    expect(find.text('상세 2'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('상세 2'), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('iOS: ☀️/🌙 같은 작은 스위치도 탭바와 같은 유리 알약이 고른 칸으로 미끄러진다', (tester) async {
    var index = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Center(
          child: StatefulBuilder(
            builder: (context, setState) => GlassSegmented(
              segments: const [
                GlassSegment(Icons.light_mode_outlined, Icons.light_mode_rounded, '화이트 모드'),
                GlassSegment(Icons.dark_mode_outlined, Icons.dark_mode_rounded, '다크 모드'),
              ],
              index: index,
              onChanged: (i) => setState(() => index = i),
            ),
          ),
        ),
      ),
    ));
    final x0 = tester.getTopLeft(find.byKey(const ValueKey('segment-selected-pill'))).dx;
    await tester.tap(find.byTooltip('다크 모드'));
    await tester.pumpAndSettle();
    expect(index, 1);
    expect(tester.getTopLeft(find.byKey(const ValueKey('segment-selected-pill'))).dx, greaterThan(x0 + 40));
    expect(find.byIcon(Icons.dark_mode_rounded), findsOneWidget); // 고른 칸은 채운 아이콘
    expect(tester.takeException(), isNull);
  });
}
