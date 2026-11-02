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
    final bottom = MediaQuery.of(context).padding.bottom;
    final dark = Sb.isDark;
    final pill = DecoratedBox(
      key: const ValueKey('tab-selected-pill'),
      decoration: BoxDecoration(
        // 선택 칸 알약: 다크는 흰 빛 유리, 화이트는 회색 둥근 배경(테두리 없음)
        color: Apple.on && !dark ? const Color(0x14000000) : Sb.tabSelected,
        borderRadius: BorderRadius.circular(999),
        border: dark ? Border.all(color: const Color(0x1FFFFFFF), width: 0.8) : null,
      ),
    );
    final bar = SizedBox(
      height: height,
      child: LayoutBuilder(builder: (context, c) {
        final w = (c.maxWidth - 12) / tabs.length;
        return Stack(children: [
          if (Apple.on)
            _JellyPill(index: index, cellWidth: w, height: height, child: pill)
          else
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
      // iOS 는 화이트 모드도 리퀴드 글래스
      child: dark || Apple.on
          ? Glass(tint: dark ? Sb.glassBar : const Color(0xB8FFFFFF), child: bar)
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

/// iOS 탭 알약: 젤리처럼 움직인다 — 가는 방향 쪽 끝이 먼저 쭉 늘어나고, 뒤쪽 끝이 탄력 있게 따라붙는다.
class _JellyPill extends StatefulWidget {
  const _JellyPill({required this.index, required this.cellWidth, required this.height, required this.child});

  final int index;
  final double cellWidth;
  final double height;
  final Widget child;

  @override
  State<_JellyPill> createState() => _JellyPillState();
}

class _JellyPillState extends State<_JellyPill> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 520));
  late int _from = widget.index;

  @override
  void didUpdateWidget(_JellyPill old) {
    super.didUpdateWidget(old);
    if (old.index != widget.index) {
      // 움직이는 중이면 지금 보이는 자리에서 다시 출발
      _from = old.index;
      _c.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (context, child) {
        final w = widget.cellWidth;
        final fromL = 6 + w * _from, toL = 6 + w * widget.index;
        final t = _c.isAnimating || _c.value > 0 ? _c.value : 1.0;
        final right = widget.index >= _from;
        // 앞쪽 끝: 빠르게(easeOutCubic), 뒤쪽 끝: 늦게 출발해 살짝 튕기며(easeOutBack)
        final lead = Curves.easeOutCubic.transform(t);
        final trail = Curves.easeOutBack.transform(Interval(0.12, 1).transform(t));
        final l = right ? fromL + (toL - fromL) * trail : fromL + (toL - fromL) * lead;
        final r = right ? fromL + w + (toL - fromL) * lead : fromL + w + (toL - fromL) * trail;
        final left = l < r ? l : r;
        final width = (r - l).abs().clamp(w * 0.6, double.infinity);
        // 움직이는 동안 살짝 납작해졌다 돌아온다
        final squash = 1 - 0.08 * (4 * t * (1 - t));
        return Positioned(
          left: left,
          top: 6 + (widget.height - 12) * (1 - squash) / 2,
          width: width,
          height: (widget.height - 12) * squash,
          child: child!,
        );
      },
      child: widget.child,
    );
  }
}

class _TabButton extends StatelessWidget {
  const _TabButton({required this.tab, required this.selected, required this.onTap});

  final GlassTab tab;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = selected ? Sb.navy : Sb.text2;
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
