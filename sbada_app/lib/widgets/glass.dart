import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

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
}

class GlassTab {
  const GlassTab(this.icon, this.selectedIcon, this.label, {this.badge});

  final IconData icon;
  final IconData selectedIcon;
  final String label;
  final ValueNotifier<int>? badge;
}

/// 화면 아래에 떠 있는 유리 캡슐 탭바(4칸). 선택된 칸 뒤에 밝은 유리 알약이 움직인다.
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
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 0, 16, bottom + 10),
      child: Glass(
        tint: Sb.glassBar,
        child: SizedBox(
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
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    // 선택 칸의 유리 알약: 다크는 흰 빛, 화이트는 민트 빛
                    color: Sb.isDark ? const Color(0x24FFFFFF) : Sb.blue.withValues(alpha: 0.35),
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: Sb.isDark ? const Color(0x1FFFFFFF) : const Color(0x14000000), width: 0.8),
                  ),
                ),
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
        ),
      ),
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
        onTap: onTap,
        radius: 32,
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          icon,
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
