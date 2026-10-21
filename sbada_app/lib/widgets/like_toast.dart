import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// 찜(하트)을 눌렀을 때 화면 아래에서 위로 페이드되며 올라오는 팝업.
///   [✓] 관심목록에 추가했어요.          관심목록 보기
/// 2.6초 뒤 다시 아래로 사라진다. 새로 뜨면 이전 팝업은 바로 닫는다.
/// 색: 화면과 반대(다크 모드 = 밝은 카드, 화이트 모드 = 어두운 카드)라서 어느 화면 위에서도 잘 보인다.
void showLikeToast(BuildContext context, {required bool liked, VoidCallback? onOpenList, double bottom = 104}) {
  _LikeToastHost.current?.dismissNow();
  final overlay = Overlay.of(context, rootOverlay: true);
  late final OverlayEntry entry;
  entry = OverlayEntry(
    builder: (_) => _LikeToast(
      liked: liked,
      bottom: bottom,
      onOpenList: onOpenList,
      onGone: () {
        if (entry.mounted) entry.remove();
      },
    ),
  );
  overlay.insert(entry);
}

class _LikeToast extends StatefulWidget {
  const _LikeToast({required this.liked, required this.bottom, required this.onGone, this.onOpenList});

  final bool liked;
  final double bottom;
  final VoidCallback onGone;
  final VoidCallback? onOpenList;

  @override
  State<_LikeToast> createState() => _LikeToastHost();
}

class _LikeToastHost extends State<_LikeToast> with SingleTickerProviderStateMixin {
  static _LikeToastHost? current;

  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 280), reverseDuration: const Duration(milliseconds: 220));
  late final Animation<double> _curve = CurvedAnimation(parent: _c, curve: Curves.easeOutCubic, reverseCurve: Curves.easeInCubic);
  bool _closing = false;

  @override
  void initState() {
    super.initState();
    current = this;
    _c.forward();
    Future.delayed(const Duration(milliseconds: 2600), _close);
  }

  Future<void> _close() async {
    if (_closing || !mounted) return;
    _closing = true;
    await _c.reverse();
    widget.onGone();
  }

  /// 새 팝업이 뜰 때: 애니메이션 없이 바로 닫는다
  void dismissNow() {
    if (_closing) return;
    _closing = true;
    widget.onGone();
  }

  @override
  void dispose() {
    if (identical(current, this)) current = null;
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bg = Sb.navy; // 다크: 밝은 회색 / 화이트: 거의 검정
    final text = Sb.base;
    final action = Sb.isDark ? const Color(0xFF137A50) : Sb.blue;
    final safe = MediaQuery.paddingOf(context).bottom;
    return Positioned(
      left: 16,
      right: 16,
      bottom: widget.bottom + safe,
      child: FadeTransition(
        opacity: _curve,
        child: SlideTransition(
          position: Tween(begin: const Offset(0, 0.8), end: Offset.zero).animate(_curve),
          child: Material(
            color: bg,
            elevation: 6,
            shadowColor: Colors.black54,
            borderRadius: BorderRadius.circular(16),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
              child: Row(children: [
                Container(
                  width: 30,
                  height: 30,
                  decoration: BoxDecoration(shape: BoxShape.circle, color: Sb.isDark ? const Color(0xFF2FB57A) : Sb.green),
                  child: const Icon(Icons.check_rounded, size: 20, color: Colors.white),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(widget.liked ? '관심목록에 추가했어요.' : '관심목록에서 뺐어요.',
                      style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: text)),
                ),
                if (widget.onOpenList != null)
                  TextButton(
                    style: TextButton.styleFrom(foregroundColor: action, minimumSize: const Size(48, 44)),
                    onPressed: () {
                      _close();
                      widget.onOpenList!();
                    },
                    child: const Text('관심목록 보기', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800)),
                  ),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}
