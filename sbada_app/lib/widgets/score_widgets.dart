import 'package:flutter/material.dart';

import '../models/recommendation.dart';
import '../theme/app_theme.dart';
import 'common.dart';

/// 매칭 점수 링(0~100%). 옅은 민트 트랙 위로 진한 민트 호가 채워지고, 가운데 숫자가 0부터 올라간다.
/// 높은 점수(75 이상)는 민트, 그 아래는 회색 계열.
class ScoreBadge extends StatelessWidget {
  const ScoreBadge(this.score, {super.key, this.size = 48, this.duration = const Duration(milliseconds: 900)});

  final int score;
  final double size;
  final Duration duration;

  bool get high => score >= 75;

  @override
  Widget build(BuildContext context) {
    final color = high ? Sb.mintText : Sb.text2;
    final track = high ? Sb.blue.withValues(alpha: Sb.isDark ? 0.18 : 0.28) : Sb.chip;
    final target = score.clamp(0, 100).toDouble();
    return Semantics(
      label: '매칭 $score%',
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0, end: target),
        duration: duration,
        curve: Curves.easeOutCubic,
        builder: (context, v, child) => SizedBox(
          width: size,
          height: size,
          child: CustomPaint(
            painter: _RingPainter(value: v / 100, color: color, track: track, stroke: size * 0.1),
            child: Center(
              child: Text.rich(
                TextSpan(children: [
                  TextSpan(text: '${v.round()}', style: TextStyle(fontSize: size * 0.3, fontWeight: FontWeight.w800)),
                  TextSpan(text: '%', style: TextStyle(fontSize: size * 0.17, fontWeight: FontWeight.w700)),
                ]),
                style: TextStyle(color: color, height: 1),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 링 없이 '93%' 숫자만 크게 보여 주는 매칭 점수(홈 목록 오른쪽 위). 75% 이상은 민트, 그 아래는 회색 계열.
/// 홈 목록은 카운트 애니메이션 없이 바로 최종 숫자를 보여 준다(사용자 요청, 2026-10-21).
class ScoreText extends StatelessWidget {
  const ScoreText(this.score, {super.key, this.fontSize = 26});

  final int score;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    final color = score >= 75 ? Sb.mintText : Sb.text2;
    return Semantics(
      label: '매칭 $score%',
      child: Text.rich(
        TextSpan(children: [
          TextSpan(text: '${score.clamp(0, 100)}', style: TextStyle(fontSize: fontSize, fontWeight: FontWeight.w900)),
          TextSpan(text: '%', style: TextStyle(fontSize: fontSize * 0.6, fontWeight: FontWeight.w800)),
        ]),
        style: TextStyle(color: color, height: 1, letterSpacing: -0.5),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  const _RingPainter({required this.value, required this.color, required this.track, required this.stroke});

  final double value;
  final Color color;
  final Color track;
  final double stroke;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = (Offset.zero & size).deflate(stroke / 2);
    final base = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = track;
    canvas.drawArc(rect, 0, 6.2832, false, base);
    if (value <= 0) return;
    final arc = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round
      ..color = color;
    // 12시 방향에서 시계 방향으로 채운다
    canvas.drawArc(rect, -1.5708, 6.2832 * value.clamp(0, 1), false, arc);
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.value != value || old.color != color || old.track != track || old.stroke != stroke;
}

/// 0 → [value] 로 채워지는 막대(점수 요소용). [delay] 만큼 늦게 시작해 위에서부터 차례로 차오르게 쓸 수 있다.
class AnimatedBar extends StatelessWidget {
  const AnimatedBar({super.key, required this.value, this.height = 6, this.delay = Duration.zero});

  final double value;
  final double height;
  final Duration delay;

  @override
  Widget build(BuildContext context) {
    const fill = Duration(milliseconds: 900);
    final total = fill + delay;
    final start = delay.inMilliseconds / total.inMilliseconds;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: value.clamp(0, 1).toDouble()),
      duration: total,
      curve: Interval(start, 1, curve: Curves.easeOutCubic),
      builder: (context, v, child) => ClipRRect(
        borderRadius: BorderRadius.circular(height / 2),
        child: LinearProgressIndicator(
          value: v,
          minHeight: height,
          backgroundColor: Sb.blue.withValues(alpha: Sb.isDark ? 0.14 : 0.22),
          color: Sb.isDark ? Sb.blue : Sb.mintText,
        ),
      ),
    );
  }
}

/// '왜 추천됐나요?' — 점수 구성 막대(값 × 가중치)
class FactorBars extends StatelessWidget {
  const FactorBars(this.factors, {super.key});

  final List<ScoreFactor> factors;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        for (var i = 0; i < factors.length; i++)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Row(children: [
              SizedBox(width: 108, child: Text(factors[i].label, style: TextStyle(fontSize: 13, color: Sb.text2))),
              Expanded(
                child: AnimatedBar(
                    value: factors[i].value, height: 8, delay: Duration(milliseconds: 120 * i)),
              ),
              SizedBox(
                width: 86,
                child: Text(
                  '${(factors[i].value * 100).round()} × ${(factors[i].weight * 100).round()}%',
                  textAlign: TextAlign.right,
                  style: TextStyle(fontSize: 12, color: Sb.text3),
                ),
              ),
            ]),
          ),
      ],
    );
  }
}

/// 추천 이유 칩. 가장 큰 이유(첫 칩)는 민트, 나머지는 회색(디자인 시안). [extra] 는 같은 줄 끝에 붙는다.
class ReasonChips extends StatelessWidget {
  const ReasonChips(this.reasons, {super.key, this.max = 3, this.extra = const []});

  final List<String> reasons;
  final int max;
  final List<Widget> extra;

  @override
  Widget build(BuildContext context) {
    final shown = reasons.take(max).toList();
    return Wrap(spacing: 6, runSpacing: 6, children: [
      for (var i = 0; i < shown.length; i++)
        i == 0
            ? Pill(shown[i], color: Sb.blueSoft, textColor: Sb.mintText, bold: true)
            : Pill(shown[i], color: Sb.chip, textColor: Sb.text2),
      ...extra,
    ]);
  }
}

/// 점수 계산 설명 바텀시트(시연용)
void showScoreSheet(BuildContext context, Recommendation rec, {required String title}) {
  showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    backgroundColor: Sb.card,
    builder: (context) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            ScoreBadge(rec.score, size: 56),
            const SizedBox(width: 16),
            Expanded(child: Text(title, style: sbH2)),
          ]),
          const SizedBox(height: 20),
          FactorBars(rec.factors),
          const SizedBox(height: 12),
          Text(
            '최종 점수 = Σ(요소 점수 × 가중치) = '
            '${rec.factors.map((f) => '${(f.value * 100).round()}×${f.weight}').join(' + ')} ≈ ${rec.score}점',
            style: sbCaption,
          ),
          const SizedBox(height: 12),
          ReasonChips(rec.reasons, max: 6),
        ]),
      ),
    ),
  );
}
