import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import 'common.dart';
import 'like_toast.dart';

/// 상세 화면 왼쪽 아래 찜 하트.
///  - 빈 하트 ↔ 채운 하트(민트). 누르면 1.0 → 1.3 → 1.0 으로 통통 튄다.
///  - 누른 뒤 아래에서 올라오는 팝업(showLikeToast)에 '관심목록 보기'를 보여 준다.
///  - 상태는 AppServices.likedIds 를 같이 보므로 관심목록 · 다른 화면과 항상 같다.
class LikeHeartButton extends StatefulWidget {
  const LikeHeartButton({super.key, required this.services, required this.itemId, this.onOpenList});

  final AppServices services;
  final int itemId;
  final VoidCallback? onOpenList;

  @override
  State<LikeHeartButton> createState() => _LikeHeartButtonState();
}

class _LikeHeartButtonState extends State<LikeHeartButton> with SingleTickerProviderStateMixin {
  late final AnimationController _pop = AnimationController(vsync: this, duration: const Duration(milliseconds: 320));
  late final Animation<double> _scale = TweenSequence<double>([
    TweenSequenceItem(tween: Tween(begin: 1.0, end: 1.3).chain(CurveTween(curve: Curves.easeOut)), weight: 40),
    TweenSequenceItem(tween: Tween(begin: 1.3, end: 1.0).chain(CurveTween(curve: Curves.elasticOut)), weight: 60),
  ]).animate(_pop);
  bool _busy = false;

  @override
  void dispose() {
    _pop.dispose();
    super.dispose();
  }

  Future<void> _tap() async {
    if (_busy) return;
    _busy = true;
    final willLike = !widget.services.likedIds.value.contains(widget.itemId);
    _pop.forward(from: 0);
    try {
      await widget.services.toggleLike(widget.itemId);
      if (mounted) showLikeToast(context, liked: willLike, onOpenList: widget.onOpenList, bottom: 92);
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      _busy = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Set<int>>(
      valueListenable: widget.services.likedIds,
      builder: (context, ids, child) {
        final liked = ids.contains(widget.itemId);
        return Semantics(
          button: true,
          toggled: liked,
          label: liked ? '찜 해제' : '찜하기',
          child: SizedBox(
            width: 52,
            height: 54,
            child: InkResponse(
              onTap: _tap,
              radius: 26,
              child: ScaleTransition(
                scale: _scale,
                child: Icon(
                  liked ? Icons.favorite_rounded : Icons.favorite_border_rounded,
                  size: 30,
                  color: liked ? (Sb.isDark ? Sb.blue : Sb.mintText) : Sb.text2,
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}
