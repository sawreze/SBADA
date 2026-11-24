import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../util/apple.dart';

/// 리퀴드 글래스 버튼(디자인 캔버스 기준, 2026-11-24).
///  - primary: 민트 유리(결제하기 · 수락 · 다음 같은 주 버튼)
///  - 그 밖: 흰 유리(거절 · 충전 · 보조 버튼). 다크 모드에서는 어두운 유리로 바뀐다.
/// 뒤 화면을 흐리게 비추고(BackdropFilter), 윗면 빛 테 + 아랫면 그늘로 두께감을 준다.
class LiquidButton extends StatelessWidget {
  const LiquidButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.primary = false,
    this.leading,
    this.height = 54,
    this.busy = false,
    this.fontSize = 16,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool primary;
  final Widget? leading;
  final double height;
  final bool busy;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null && !busy;
    final dark = Sb.isDark;
    final r = BorderRadius.circular(height / 2);
    final colors = primary
        ? const [Color(0xD9B2ECCC), Color(0x9493E1B7), Color(0xB893E1B7)]
        : dark
            ? const [Color(0x40FFFFFF), Color(0x1FFFFFFF), Color(0x2EFFFFFF)]
            : const [Color(0xD1FFFFFF), Color(0x7AFFFFFF), Color(0x9EFFFFFF)];
    final fg = primary ? Sb.onAccent : Sb.navy;
    return Opacity(
      opacity: enabled ? 1 : 0.5,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: r,
          boxShadow: [
            BoxShadow(
              color: primary ? const Color(0x332F8A63) : Colors.black.withValues(alpha: dark ? 0.3 : 0.08),
              blurRadius: primary ? 22 : 18,
              offset: const Offset(0, 8),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: r,
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
            child: Material(
              type: MaterialType.transparency,
              child: InkWell(
                onTap: enabled
                    ? () {
                        Apple.tick();
                        onPressed!();
                      }
                    : null,
                splashFactory: NoSplash.splashFactory,
                child: Ink(
                  height: height,
                  decoration: BoxDecoration(
                    borderRadius: r,
                    border: Border.all(color: Colors.white.withValues(alpha: dark && !primary ? 0.25 : 0.9)),
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: colors,
                      stops: const [0, 0.55, 1],
                    ),
                  ),
                  child: Center(
                    child: busy
                        ? SizedBox(
                            width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.4, color: fg))
                        : Row(mainAxisSize: MainAxisSize.min, children: [
                            if (leading != null) ...[leading!, const SizedBox(width: 10)],
                            Flexible(
                              child: Text(label,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(color: fg, fontSize: fontSize, fontWeight: FontWeight.w800)),
                            ),
                          ]),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
