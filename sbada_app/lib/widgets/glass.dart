import 'dart:math' as math;
import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../util/apple.dart';

/// 리퀴드 글래스 표면: 뒤를 흐리게(blur) + 반투명 흰색 + 얇은 밝은 테두리 + 위쪽 빛 반사.
/// 안드로이드에서도 같은 느낌이 나도록 BackdropFilter 로 직접 그린다.
class Glass extends StatelessWidget {
  const Glass({
    super.key,
    required this.child,
    this.radius = 999,
    this.blur = 24,
    this.tint,
    this.padding,
  });

  final Widget child;
  final double radius;
  final double blur;

  /// 비워 두면 모드에 맞는 유리색(Sb.glassFill)
  final Color? tint;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) {
    if (Apple.on) return _liquid();
    final br = BorderRadius.circular(radius);
    final fill = tint ?? Sb.glassFill;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: br,
        boxShadow: [BoxShadow(color: Sb.glassShadow, blurRadius: 24, offset: const Offset(0, 8))],
      ),
      child: ClipRRect(
        borderRadius: br,
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: blur, sigmaY: blur),
          child: Container(
            padding: padding,
            decoration: BoxDecoration(
              borderRadius: br,
              border: Border.all(color: Sb.glassStroke, width: 0.8),
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [Color.alphaBlend(Sb.glassHighlight, fill), fill],
              ),
            ),
            child: child,
          ),
        ),
      ),
    );
  }

  /// iOS 리퀴드 글래스: 더 강한 흐림 + 뒤 색을 또렷하게(채도 ↑) + 가장자리 빛 굴절(왼쪽 위 밝게, 오른쪽 아래 은은하게)
  Widget _liquid() {
    final br = BorderRadius.circular(radius);
    final fill = tint ?? Sb.glassFill;
    // 유리를 조금 더 투명하게 해서 뒤가 비쳐 보이게
    final clear = fill.withValues(alpha: (fill.a * 0.78).clamp(0.0, 1.0));
    final sigma = blur < 30 ? 30.0 : blur;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: br,
        boxShadow: [
          BoxShadow(color: Sb.glassShadow, blurRadius: 30, offset: const Offset(0, 10)),
          BoxShadow(color: Colors.black.withValues(alpha: 0.04), blurRadius: 2, offset: const Offset(0, 1)),
        ],
      ),
      child: ClipRRect(
        borderRadius: br,
        child: BackdropFilter(
          filter: ImageFilter.compose(
            outer: const ColorFilter.matrix(_saturate),
            inner: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
          ),
          child: CustomPaint(
            foregroundPainter: _RimPainter(radius: radius, dark: Sb.isDark),
            child: Container(
              padding: padding,
              decoration: BoxDecoration(
                borderRadius: br,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [Color.alphaBlend(Sb.glassHighlight, clear), clear, Color.alphaBlend(const Color(0x08FFFFFF), clear)],
                  stops: const [0, 0.55, 1],
                ),
              ),
              child: child,
            ),
          ),
        ),
      ),
    );
  }
}

/// 채도 1.6배(뒤에 비친 색이 유리를 지나며 살아나는 느낌)
const List<double> _saturate = [
  1.4728, -0.4288, -0.0440, 0, 0, //
  -0.1272, 1.1712, -0.0440, 0, 0, //
  -0.1272, -0.4288, 1.5560, 0, 0, //
  0, 0, 0, 1, 0,
];

/// 유리 가장자리의 빛: 왼쪽 위는 밝은 테, 가운데는 거의 안 보이고, 오른쪽 아래는 다시 은은하게(빛이 굴절돼 반대편에 맺힘)
class _RimPainter extends CustomPainter {
  _RimPainter({required this.radius, required this.dark});

  final double radius;
  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final r = radius.clamp(0.0, size.shortestSide / 2);
    final rect = Offset.zero & size;
    final rrect = RRect.fromRectAndRadius(rect.deflate(0.5), Radius.circular(r));
    final hi = dark ? 0x66 : 0xE6;
    final rim = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0
      ..shader = LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(hi << 24 | 0xFFFFFF), Color(0x14 << 24 | 0xFFFFFF), Color((dark ? 0x33 : 0x99) << 24 | 0xFFFFFF)],
        stops: const [0, 0.5, 1],
      ).createShader(rect);
    canvas.drawRRect(rrect, rim);
    // 안쪽 위 가장자리를 따라 아주 얇은 빛 한 줄(유리 두께감)
    final inner = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.center,
        colors: [Color((dark ? 0x22 : 0x55) << 24 | 0xFFFFFF), const Color(0x00FFFFFF)],
      ).createShader(rect);
    canvas.drawRRect(rrect.deflate(1.4), inner);
  }

  @override
  bool shouldRepaint(_RimPainter old) => old.radius != radius || old.dark != dark;
}

class GlassTab {
  const GlassTab(this.icon, this.selectedIcon, this.label, {this.badge});

  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final ValueNotifier<int>? badge;
}

/// 화면 아래에 떠 있는 캡슐 탭바(4칸). 선택된 칸 뒤에 알약이 움직인다.
///  - 다크: 리퀴드 글래스(흐림 + 반투명) + 흰 빛 알약
///  - 화이트: 당근 스타일(2026-10-22 사용자 요청) — 불투명 흰 캡슐 + 옅은 그림자, 선택 칸은 회색 둥근 배경
/// 크기: 높이 64dp, 칸마다 최소 48dp 터치 영역(안드로이드 접근성 기준)
class GlassTabBar extends StatelessWidget {
  const GlassTabBar({super.key, required this.tabs, required this.index, required this.onTap});

  final List<GlassTab> tabs;
  final int index;
  final ValueChanged<int> onTap;

  static const height = 64.0;

  @override
  Widget build(BuildContext context) {
    // iOS: 렌즈처럼 커지는 리퀴드 글래스 탭바(아래 _AppleTabBar)
    if (Apple.on) return _AppleTabBar(tabs: tabs, index: index, onTap: onTap);
    final bottom = MediaQuery.of(context).padding.bottom;
    final dark = Sb.isDark;
    final pill = DecoratedBox(
      key: const ValueKey('tab-selected-pill'),
      decoration: BoxDecoration(
        // 선택 칸 알약: 다크는 흰 빛 유리, 화이트는 회색 둥근 배경(테두리 없음)
        color: Sb.tabSelected,
        borderRadius: BorderRadius.circular(999),
        border: dark ? Border.all(color: const Color(0x1FFFFFFF), width: 0.8) : null,
      ),
    );
    final bar = SizedBox(
      height: height,
      child: LayoutBuilder(builder: (context, c) {
        final w = (c.maxWidth - 12) / tabs.length;
        return Stack(children: [
          AnimatedPositioned(
            duration: const Duration(milliseconds: 260),
            curve: Curves.easeOutCubic,
            left: 6 + w * index,
            top: 6,
            width: w,
            height: height - 12,
            child: pill,
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: Row(children: [
              for (var i = 0; i < tabs.length; i++)
                Expanded(child: _TabButton(tab: tabs[i], selected: i == index, onTap: () => onTap(i))),
            ]),
          ),
        ]);
      }),
    );
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, bottom + 10),
      child: dark
          ? Glass(tint: Sb.glassBar, child: bar)
          // 화이트: 흐림(BackdropFilter) 없이 불투명 흰 캡슐 + 옅은 그림자 — 그리기도 가볍다
          : DecoratedBox(
              key: const ValueKey('tab-bar-white'),
              decoration: BoxDecoration(
                color: Sb.base,
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: Sb.line, width: 0.8),
                boxShadow: const [BoxShadow(color: Color(0x1A000000), blurRadius: 16, offset: Offset(0, 4))],
              ),
              child: bar,
            ),
    );
  }
}

/// iOS 리퀴드 글래스 탭바(iOS 26 · 2026-11-03 사용자 영상과 같은 모양).
///  - 맑은 흰 유리: 뒤 화면(사진 · 목록)이 살짝 흐리게 비쳐 보이고, 가장자리에 빛 테. 다크 모드에서도 같은 흰 유리
///  - 가만히 있을 때: 선택 칸 뒤에 옅은 회색 유리 알약, 아이콘은 검정(선택 칸은 채운 아이콘)
///  - 탭을 누르거나 손가락으로 끌면: 알약이 탭바보다 큰 '투명 렌즈'로 부풀어 아래 아이콘을 확대해 보여 주고(RawMagnifier),
///    위 · 아래 가장자리에 무지개빛(빛 굴절) 테가 생긴다. 탭바도 살짝 늘어난다.
///  - 손가락으로 끌기: 렌즈가 손가락을 따라 다니고, 칸을 지날 때마다 '톡' 진동, 놓으면 가장 가까운 칸으로 붙으며 그 탭이 열린다.
class _AppleTabBar extends StatefulWidget {
  const _AppleTabBar({required this.tabs, required this.index, required this.onTap});

  final List<GlassTab> tabs;
  final int index;
  final ValueChanged<int> onTap;

  @override
  State<_AppleTabBar> createState() => _AppleTabBarState();
}

class _AppleTabBarState extends State<_AppleTabBar> with TickerProviderStateMixin {
  static const _h = GlassTabBar.height;

  /// 칸 이동 애니메이션(0 → 1)
  late final AnimationController _move = AnimationController(vsync: this, duration: const Duration(milliseconds: 620))
    ..value = 1;

  /// 손가락으로 누르고 있는 동안 렌즈가 부푼 정도(0 → 1)
  late final AnimationController _lift = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 260), reverseDuration: const Duration(milliseconds: 420));

  /// 이동 출발 위치(칸 단위, 소수 가능 — 끌다 놓은 자리에서 출발할 때)
  late double _fromPos = widget.index.toDouble();

  /// 끄는 중인 렌즈 가운데(칸 단위). null 이면 끌지 않는 중
  double? _dragPos;
  double? _releasedAt;
  double _cellW = 1;

  @override
  void didUpdateWidget(_AppleTabBar old) {
    super.didUpdateWidget(old);
    if (old.index != widget.index) {
      _fromPos = _releasedAt ?? _currentPos(old.index);
      _releasedAt = null;
      _move.forward(from: 0);
    }
  }

  /// 지금 알약이 있는 자리(칸 단위) — 움직이는 중에 또 누르면 그 자리에서 다시 출발
  double _currentPos(int oldIndex) {
    final t = Curves.easeOutCubic.transform(_move.value);
    return _fromPos + (oldIndex - _fromPos) * t;
  }

  @override
  void dispose() {
    _move.dispose();
    _lift.dispose();
    super.dispose();
  }

  double _posAt(double dx) => ((dx - 6) / _cellW - 0.5).clamp(0.0, widget.tabs.length - 1.0);

  void _dragStart(DragStartDetails d) {
    _dragPos = _posAt(d.localPosition.dx);
    _lift.forward();
    Apple.tick();
    setState(() {});
  }

  void _dragUpdate(DragUpdateDetails d) {
    final old = _dragPos;
    final p = _posAt(d.localPosition.dx);
    if (old != null && old.round() != p.round()) Apple.tick(); // 칸을 넘을 때마다 톡
    setState(() => _dragPos = p);
  }

  void _dragEnd([DragEndDetails? d]) {
    final p = _dragPos;
    if (p == null) return;
    // 빠르게 튕기면 그 방향 칸으로
    final v = (d?.primaryVelocity ?? 0) / _cellW;
    final target = (p + (v.abs() > 6 ? v.sign * 0.5 : 0)).round().clamp(0, widget.tabs.length - 1);
    setState(() => _dragPos = null);
    _lift.reverse();
    if (target != widget.index) {
      _releasedAt = p;
      widget.onTap(target);
    } else {
      _fromPos = p; // 같은 칸이면 그 자리로 돌아가기만
      _move.forward(from: 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final bottom = MediaQuery.of(context).padding.bottom;
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, bottom + 10),
      child: SizedBox(
        height: _h,
        child: LayoutBuilder(builder: (context, box) {
          final n = widget.tabs.length;
          final w = (box.maxWidth - 12) / n;
          _cellW = w;
          return GestureDetector(
            behavior: HitTestBehavior.translucent,
            onHorizontalDragStart: _dragStart,
            onHorizontalDragUpdate: _dragUpdate,
            onHorizontalDragEnd: _dragEnd,
            onHorizontalDragCancel: _dragEnd,
            child: AnimatedBuilder(
              animation: Listenable.merge([_move, _lift]),
              builder: (context, _) {
                final t = _move.value;
                final moveM = _move.isAnimating ? math.sqrt((4 * t * (1 - t)).clamp(0.0, 1.0)) : 0.0;
                final m = math.max(moveM, Curves.easeOut.transform(_lift.value));
                double pillL, pillW;
                final drag = _dragPos;
                if (drag != null) {
                  pillL = 6 + w * drag;
                  pillW = w;
                } else {
                  // 젤리: 가는 쪽 끝이 먼저, 뒤쪽 끝이 튕기며 따라온다
                  final fromL = 6 + w * _fromPos, toL = 6 + w * widget.index;
                  final right = toL >= fromL;
                  final lead = Curves.easeOutCubic.transform(t);
                  final trail = Curves.easeOutBack.transform(const Interval(0.12, 1).transform(t));
                  final l = fromL + (toL - fromL) * (right ? trail : lead);
                  final r = fromL + w + (toL - fromL) * (right ? lead : trail);
                  pillL = math.min(l, r);
                  pillW = math.max((r - l).abs(), w * 0.6);
                }
                final cx = pillL + pillW / 2;
                final lensW = pillW + 26 * m, lensH = (_h - 12) + 34 * m;
                return Stack(clipBehavior: Clip.none, children: [
                  // ① 맑은 흰 유리 탭바(누르는 동안 살짝 늘어남)
                  Positioned.fill(
                    child: Transform.scale(
                      scaleX: 1 + 0.02 * m,
                      scaleY: 1 + 0.06 * m,
                      child: const _ClearGlassBar(),
                    ),
                  ),
                  // ② 쉬고 있을 때의 회색 유리 알약(렌즈가 커지면 사라짐)
                  Positioned(
                    left: pillL,
                    top: 6,
                    width: pillW,
                    height: _h - 12,
                    child: Opacity(
                      opacity: (1 - m * 1.6).clamp(0.0, 1.0),
                      child: const DecoratedBox(
                        key: ValueKey('tab-selected-pill'),
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.all(Radius.circular(999)),
                          gradient: LinearGradient(
                            begin: Alignment.topCenter,
                            end: Alignment.bottomCenter,
                            colors: [Color(0x1A000000), Color(0x24000000)],
                          ),
                        ),
                      ),
                    ),
                  ),
                  // ③ 탭 버튼들(흰 유리 위라 아이콘은 검정)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 6),
                    child: Row(children: [
                      for (var i = 0; i < n; i++)
                        Expanded(
                          child: _TabButton(
                            tab: widget.tabs[i],
                            selected: i == widget.index,
                            onTap: () => widget.onTap(i),
                            onLightGlass: true,
                          ),
                        ),
                    ]),
                  ),
                  // ④ 누르거나 움직이는 동안: 아래를 확대해 보여 주는 투명 렌즈 + 무지개빛 테
                  if (m > 0.02)
                    Positioned(
                      left: cx - lensW / 2,
                      top: _h / 2 - lensH / 2,
                      width: lensW,
                      height: lensH,
                      child: IgnorePointer(
                        child: Stack(children: [
                          RawMagnifier(
                            size: Size(lensW, lensH),
                            magnificationScale: 1 + 0.22 * m,
                            decoration: MagnifierDecoration(
                              shape: const StadiumBorder(),
                              shadows: [
                                BoxShadow(
                                    color: Colors.black.withValues(alpha: 0.12 * m), blurRadius: 18, offset: const Offset(0, 6)),
                              ],
                            ),
                          ),
                          // 렌즈 안쪽을 아주 살짝 밝게(유리 느낌)
                          Positioned.fill(
                            child: DecoratedBox(
                              decoration: ShapeDecoration(
                                shape: const StadiumBorder(),
                                gradient: LinearGradient(
                                  begin: Alignment.topCenter,
                                  end: Alignment.bottomCenter,
                                  colors: [Colors.white.withValues(alpha: 0.16 * m), Colors.white.withValues(alpha: 0.04 * m)],
                                ),
                              ),
                            ),
                          ),
                          Positioned.fill(child: CustomPaint(painter: _LensRimPainter(m))),
                        ]),
                      ),
                    ),
                ]);
              },
            ),
          );
        }),
      ),
    );
  }
}

/// 영상 속 탭바 유리: 흐림은 약하게(뒤가 비쳐 보이게) + 반투명 흰색 + 채도 ↑ + 왼쪽 위가 밝은 빛 테 + 옅은 그림자
class _ClearGlassBar extends StatelessWidget {
  const _ClearGlassBar();

  @override
  Widget build(BuildContext context) {
    const br = BorderRadius.all(Radius.circular(999));
    return DecoratedBox(
      decoration: const BoxDecoration(
        borderRadius: br,
        boxShadow: [
          BoxShadow(color: Color(0x26000000), blurRadius: 24, offset: Offset(0, 8)),
          BoxShadow(color: Color(0x0F000000), blurRadius: 2, offset: Offset(0, 1)),
        ],
      ),
      child: ClipRRect(
        borderRadius: br,
        child: BackdropFilter(
          filter: ImageFilter.compose(
            outer: const ColorFilter.matrix(_saturate),
            inner: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
          ),
          child: CustomPaint(
            foregroundPainter: _RimPainter(radius: 999, dark: false),
            child: const DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: br,
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  // 2026-11-03: 조금 더 투명하게(뒤가 더 비쳐 보이게)
                  colors: [Color(0x99FFFFFF), Color(0x73FFFFFF), Color(0x85F4F5F7)],
                  stops: [0, 0.5, 1],
                ),
              ),
              child: SizedBox.expand(),
            ),
          ),
        ),
      ),
    );
  }
}

/// 렌즈 가장자리: 얇은 흰 테 + 위 · 아래 가장자리에 맺히는 무지개빛(빛이 유리 두께에서 갈라짐) + 안쪽 그늘(두께감)
class _LensRimPainter extends CustomPainter {
  _LensRimPainter(this.m);

  final double m;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final rr = RRect.fromRectAndRadius(rect.deflate(1), Radius.circular(size.height / 2));
    // 안쪽 그늘: 가장자리를 따라 아주 옅게
    canvas.drawRRect(
      rr.deflate(3),
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 6
        ..color = Colors.black.withValues(alpha: 0.05 * m)
        ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 4),
    );
    // 무지개빛: 위 · 아래 가운데에 몰리게(양옆은 투명)
    final rainbow = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.2
      ..maskFilter = const MaskFilter.blur(BlurStyle.normal, 1.6)
      ..shader = SweepGradient(
        colors: [
          const Color(0x00FFFFFF),
          const Color(0xCC40C8FF),
          const Color(0xCCFF4FD8),
          const Color(0xCCFFD23F),
          const Color(0x00FFFFFF),
          const Color(0x00FFFFFF),
          const Color(0xCCFFD23F),
          const Color(0xCCFF4FD8),
          const Color(0xCC40C8FF),
          const Color(0x00FFFFFF),
          const Color(0x00FFFFFF),
        ],
        stops: const [0.02, 0.12, 0.2, 0.28, 0.4, 0.6, 0.72, 0.8, 0.88, 0.98, 1.0],
      ).createShader(rect);
    canvas.saveLayer(rect, Paint()..color = Colors.white.withValues(alpha: m));
    canvas.drawRRect(rr, rainbow);
    canvas.restore();
    // 얇은 흰 테(왼쪽 위가 더 밝게)
    canvas.drawRRect(
      rr,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1
        ..shader = LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Colors.white.withValues(alpha: 0.95 * m), Colors.white.withValues(alpha: 0.25 * m), Colors.white.withValues(alpha: 0.6 * m)],
        ).createShader(rect),
    );
  }

  @override
  bool shouldRepaint(_LensRimPainter old) => old.m != m;
}

class _TabButton extends StatelessWidget {
  const _TabButton({required this.tab, required this.selected, required this.onTap, this.onLightGlass = false});

  final GlassTab tab;
  final bool selected;
  final VoidCallback onTap;

  /// iOS 흰 유리 탭바 위: 다크 모드여도 아이콘 · 글자를 검정으로
  final bool onLightGlass;

  @override
  Widget build(BuildContext context) {
    final color = onLightGlass
        ? (selected ? const Color(0xFF111111) : const Color(0xFF2C2C2E))
        : (selected ? Sb.navy : Sb.text2);
    Widget icon = Icon(selected ? tab.selectedIcon : tab.icon, size: 24, color: color);
    if (tab.badge != null) {
      icon = ValueListenableBuilder<int>(
        valueListenable: tab.badge!,
        builder: (context, n, child) => Badge(
          isLabelVisible: n > 0,
          label: Text(n > 99 ? '99+' : '$n', style: const TextStyle(fontSize: 10, color: Sb.onAccent)),
          backgroundColor: Sb.blue,
          child: child,
        ),
        child: icon,
      );
    }
    return Semantics(
      button: true,
      selected: selected,
      label: tab.label,
      child: InkResponse(
        onTap: () {
          if (!selected) Apple.tick();
          onTap();
        },
        radius: 32,
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          // iOS: 선택되는 순간 아이콘이 톡 커졌다 돌아온다
          Apple.on
              ? AnimatedScale(
                  scale: selected ? 1.08 : 1.0,
                  duration: const Duration(milliseconds: 420),
                  curve: Curves.elasticOut,
                  child: icon,
                )
              : icon,
          const SizedBox(height: 3),
          Text(tab.label,
              maxLines: 1,
              style: TextStyle(fontSize: 11, fontWeight: selected ? FontWeight.w700 : FontWeight.w500, color: color)),
        ]),
      ),
    );
  }
}

/// 탭바 위에 떠 있는 유리 버튼(＋ 물건 올리기)
class GlassFab extends StatelessWidget {
  const GlassFab({super.key, required this.label, required this.icon, required this.onPressed});

  final String label;
  final IconData icon;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Glass(
      tint: const Color(0xE693E1B7),
      child: Material(
        type: MaterialType.transparency,
        child: InkWell(
          onTap: onPressed,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 13),
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              Icon(icon, color: Sb.onAccent, size: 20),
              const SizedBox(width: 6),
              Text(label, style: const TextStyle(color: Sb.onAccent, fontSize: 15, fontWeight: FontWeight.w700)),
            ]),
          ),
        ),
      ),
    );
  }
}

/// 둥근 유리 아이콘 버튼(지도 위 버튼 등). 48dp 터치 영역
class GlassIconButton extends StatelessWidget {
  const GlassIconButton({super.key, required this.icon, required this.onPressed, this.tooltip});

  final IconData icon;
  final VoidCallback onPressed;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: tooltip ?? '',
      child: Glass(
        tint: Sb.glassButton,
        child: Material(
          type: MaterialType.transparency,
          child: InkWell(
            onTap: onPressed,
            child: SizedBox(width: 48, height: 48, child: Icon(icon, color: Sb.navy, size: 22)),
          ),
        ),
      ),
    );
  }
}
