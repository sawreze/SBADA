import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// 묶음 메뉴 카드(계정정보 · 앱 설정). 사용자가 준 시안:
///   ┌ 설정 ───────────────────────┐
///   │ ◎  내 동네 설정            > │
///   │ ⚙  앱 설정                 > │
///   └─────────────────────────────┘
/// 다크: 카드 #1A1C20 / 화이트: 옅은 회색(bg) — 바탕과 구분된다.
class SettingsGroup extends StatelessWidget {
  const SettingsGroup({super.key, this.title, required this.children});

  final String? title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      padding: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(color: Sb.isDark ? Sb.card : Sb.bg, borderRadius: BorderRadius.circular(16)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (title != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 4),
            child: Text(title!, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: Sb.text3)),
          ),
        ...children,
      ]),
    );
  }
}

/// 묶음 카드 안의 한 줄: [아이콘] 글자 (부가 설명)        (오른쪽 값/배지) >
class SettingsRow extends StatelessWidget {
  const SettingsRow({
    super.key,
    required this.icon,
    required this.label,
    this.onTap,
    this.subtitle,
    this.trailing,
    this.showChevron = true,
  });

  final IconData icon;
  final String label;
  final String? subtitle;
  final VoidCallback? onTap;
  final Widget? trailing;
  final bool showChevron;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 52),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Row(children: [
            Icon(icon, size: 22, color: Sb.navy),
            const SizedBox(width: 14),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
                Text(label, style: TextStyle(fontSize: 15, color: Sb.navy)),
                if (subtitle != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: Text(subtitle!,
                        maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 12, color: Sb.text3)),
                  ),
              ]),
            ),
            if (trailing != null) ...[const SizedBox(width: 8), trailing!],
            if (showChevron) ...[const SizedBox(width: 4), Icon(Icons.chevron_right_rounded, color: Sb.text3)],
          ]),
        ),
      ),
    );
  }
}

/// 오른쪽 민트 숫자 배지(0이면 숨김)
class CountBadge extends StatelessWidget {
  const CountBadge(this.count, {super.key});

  final ValueListenable<int> count;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<int>(
      valueListenable: count,
      builder: (context, n, child) => n <= 0
          ? const SizedBox.shrink()
          : Container(
              constraints: const BoxConstraints(minWidth: 22),
              height: 22,
              padding: const EdgeInsets.symmetric(horizontal: 6),
              alignment: Alignment.center,
              decoration: BoxDecoration(color: Sb.blue, borderRadius: BorderRadius.circular(11)),
              child: Text('$n', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800, color: Sb.onAccent)),
            ),
    );
  }
}

/// 오른쪽 회색 값(예: '3개', '마포구 망원동')
class TrailingText extends StatelessWidget {
  const TrailingText(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Text(text, style: TextStyle(fontSize: 14, color: Sb.text3));
}
