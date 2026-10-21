import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'net_image.dart';

/// 동네지도 핀: 물방울 모양 핀의 위쪽 원에 '올린 물건 사진', 아래쪽에 '매치도 %'.
/// 매치도가 높을수록 핀 테두리를 따라 도는 초록 빛이 더 밝고 넓게 번진다.
///
///   ┌──────┐
///   │ (사진) │  ← 위쪽 원(사진)
///   │  87%  │  ← 매치도
///    \    /
///      \/    ← 꼬리 끝 = 물건 위치
class MatchPin extends StatefulWidget {
  const MatchPin({
    super.key,
    required this.imagePath,
    required this.score,
    this.selected = false,
    this.width = 64,
  });

  /// 서버 사진 경로(/api/images/3)
  final String? imagePath;

  /// 0~100. null 이면 아직 매치도를 모르는 물건(빛 없음, '–' 표시)
  final int? score;
  final bool selected;
  final double width;

  /// 핀 둘레에 빛이 번질 여백
  static const glowPad = 12.0;

  double get height => width * 1.375;

  /// 위젯 전체 크기(빛 여백 포함). 꼬리 끝은 (가로 가운데, 아래에서 glowPad 위)에 있다.
  Size get outerSize => Size(width + glowPad * 2, height + glowPad * 2);

  @override
  State<MatchPin> createState() => _MatchPinState();
}

/// 매치도 → 빛의 세기(0~1). 40% 이하는 거의 빛나지 않고, 100%에 가까울수록 1.
double glowStrength(int? score) {
  if (score == null) return 0;
  return ((score - 40) / 60).clamp(0.0, 1.0).toDouble();
}

/// 빛이 한 바퀴 도는 시간: 매치도가 높을수록 조금 더 빠르게(3.2초 → 1.8초)
Duration glowPeriod(int? score) =>
    Duration(milliseconds: (3200 - 1400 * glowStrength(score)).round());

class _MatchPinState extends State<MatchPin> with SingleTickerProviderStateMixin {
  late final AnimationController _spin = AnimationController(vsync: this, duration: glowPeriod(widget.score));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _syncAnimation();
  }

  @override
  void didUpdateWidget(MatchPin old) {
    super.didUpdateWidget(old);
    if (old.score != widget.score) {
      _spin.duration = glowPeriod(widget.score);
      _syncAnimation();
    }
  }

  void _syncAnimation() {
    // 움직임 줄이기 설정이면 빛을 돌리지 않고 멈춰 둔다
    final reduce = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
    if (reduce || glowStrength(widget.score) == 0) {
      _spin.stop();
    } else if (!_spin.isAnimating) {
      _spin.repeat();
    }
  }

  @override
  void dispose() {
    _spin.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final w = widget.width;
    final h = widget.height;
    const pad = MatchPin.glowPad;
    final r = w / 2; // 바깥 원 반지름
    final photoR = r * 0.66; // 사진 원 반지름
    final photoCy = r * 0.86; // 사진 원 중심(바깥 원보다 조금 위)
    final fill = widget.selected ? Sb.blue : Sb.pinFill;
    final scoreText = widget.score == null ? '–' : '${widget.score}%';

    return SizedBox.fromSize(
      size: widget.outerSize,
      child: Stack(clipBehavior: Clip.none, children: [
        // 핀 몸통 + 테두리를 따라 도는 초록 빛
        Positioned.fill(
          child: RepaintBoundary(
            child: AnimatedBuilder(
              animation: _spin,
              builder: (context, _) => CustomPaint(
                painter: _PinPainter(
                  fill: fill,
                  stroke: widget.selected ? Sb.onAccent : Sb.glassStroke,
                  strength: glowStrength(widget.score),
                  turn: _spin.value,
                  pad: pad,
                  w: w,
                  h: h,
                ),
              ),
            ),
          ),
        ),
        // 위쪽 원: 올린 물건 사진
        Positioned(
          left: pad + r - photoR,
          top: pad + photoCy - photoR,
          width: photoR * 2,
          height: photoR * 2,
          child: Container(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 2),
            ),
            child: ClipOval(child: NetImage(widget.imagePath, size: photoR * 2, radius: 0)),
          ),
        ),
        // 아래쪽: 매치도
        Positioned(
          left: pad,
          width: w,
          top: pad + photoCy + photoR + 1,
          child: Text(
            scoreText,
            textAlign: TextAlign.center,
            maxLines: 1,
            style: TextStyle(
              fontSize: w * 0.19,
              height: 1.1,
              fontWeight: FontWeight.w900,
              color: widget.selected ? Sb.onAccent : const Color(0xFF7CF5A8),
            ),
          ),
        ),
      ]),
    );
  }
}

/// 물방울 핀 모양: 위쪽 원(반지름 w/2) + 아래 꼭짓점으로 이어지는 두 접선
Path pinPath(double w, double h, {Offset origin = Offset.zero}) {
  final r = w / 2;
  final c = origin + Offset(r, r);
  final tip = origin + Offset(r, h);
  final d = h - r; // 원 중심 ~ 꼭짓점 거리
  final theta = math.acos(r / d); // 아래 방향에서 접점까지의 각
  final right = c + Offset(r * math.sin(theta), r * math.cos(theta));
  final start = math.pi / 2 - theta; // 오른쪽 접점의 각(Flutter: +x 기준 시계 방향)
  return Path()
    ..moveTo(tip.dx, tip.dy)
    ..lineTo(right.dx, right.dy)
    ..arcTo(Rect.fromCircle(center: c, radius: r), start, -(2 * math.pi - 2 * theta), false)
    ..close();
}

class _PinPainter extends CustomPainter {
  _PinPainter({
    required this.fill,
    required this.stroke,
    required this.strength,
    required this.turn,
    required this.pad,
    required this.w,
    required this.h,
  });

  final Color fill;
  final Color stroke;
  final double strength; // 0~1
  final double turn; // 0~1 (한 바퀴)
  final double pad;
  final double w;
  final double h;

  static const _green = Color(0xFF34E07A);
  static const _lightGreen = Color(0xFFB9FFD2);

  @override
  void paint(Canvas canvas, Size size) {
    final path = pinPath(w, h, origin: Offset(pad, pad));
    final center = Offset(pad + w / 2, pad + h * 0.45);
    final bounds = Rect.fromCircle(center: center, radius: h);

    // 그림자
    canvas.drawShadow(path, Colors.black, 4, false);

    // 테두리를 따라 도는 빛: 초록 → 밝은 초록 → 투명 이 한 바퀴를 돈다
    if (strength > 0) {
      final sweep = SweepGradient(
        center: Alignment.center,
        transform: GradientRotation(2 * math.pi * turn),
        colors: [
          _green.withValues(alpha: 0),
          _green.withValues(alpha: 0.55 + 0.45 * strength),
          _lightGreen.withValues(alpha: 0.6 + 0.4 * strength),
          _green.withValues(alpha: 0.55 + 0.45 * strength),
          _green.withValues(alpha: 0),
          _green.withValues(alpha: 0.25 * strength),
          _green.withValues(alpha: 0),
        ],
        stops: const [0.0, 0.18, 0.25, 0.32, 0.5, 0.75, 1.0],
      ).createShader(bounds);

      // 바깥으로 번지는 빛(매치도가 높을수록 넓고 진하게)
      canvas.drawPath(
        path,
        Paint()
          ..shader = sweep
          ..style = PaintingStyle.stroke
          ..strokeWidth = 4 + 8 * strength
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 3 + 7 * strength),
      );
      // 은은하게 핀 전체를 감싸는 초록 빛
      canvas.drawPath(
        path,
        Paint()
          ..color = _green.withValues(alpha: 0.12 + 0.28 * strength)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2 + 6 * strength
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 4 + 6 * strength),
      );
    }

    // 몸통
    canvas.drawPath(path, Paint()..color = fill);

    // 또렷한 테두리 선(빛이 있으면 빛 색, 없으면 유리 테두리)
    if (strength > 0) {
      canvas.drawPath(
        path,
        Paint()
          ..shader = SweepGradient(
            center: Alignment.center,
            transform: GradientRotation(2 * math.pi * turn),
            colors: [
              _green.withValues(alpha: 0.35),
              _lightGreen,
              _green.withValues(alpha: 0.35),
              _green.withValues(alpha: 0.35),
            ],
            stops: const [0.15, 0.25, 0.35, 1.0],
          ).createShader(bounds)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.5 + strength,
      );
    } else {
      canvas.drawPath(
        path,
        Paint()
          ..color = stroke
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1,
      );
    }
  }

  @override
  bool shouldRepaint(_PinPainter old) =>
      old.turn != turn || old.strength != strength || old.fill != fill || old.stroke != stroke;
}
