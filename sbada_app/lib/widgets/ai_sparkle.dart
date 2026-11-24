import 'package:flutter/material.dart';

/// AI 표시 아이콘: 변이 오목한 네 꼭짓점 별(마름모형 반짝이) + 쓰바다 색 그라데이션(노랑 → 민트 → 청록).
/// 예전 Icons.auto_awesome(선으로 그린 별 여러 개) 대신 쓴다.
class AiSparkle extends StatelessWidget {
  const AiSparkle({super.key, this.size = 18});

  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox.square(
      dimension: size,
      child: const CustomPaint(painter: _SparklePainter()),
    );
  }
}

class _SparklePainter extends CustomPainter {
  const _SparklePainter();

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 24;
    // 24x24 기준: 위 · 오른쪽 · 아래 · 왼쪽 꼭짓점을 곡선으로 안쪽으로 오목하게 잇는다
    final path = Path()
      ..moveTo(12 * s, 1 * s)
      ..cubicTo(12.9 * s, 7.6 * s, 16.4 * s, 11.1 * s, 23 * s, 12 * s)
      ..cubicTo(16.4 * s, 12.9 * s, 12.9 * s, 16.4 * s, 12 * s, 23 * s)
      ..cubicTo(11.1 * s, 16.4 * s, 7.6 * s, 12.9 * s, 1 * s, 12 * s)
      ..cubicTo(7.6 * s, 11.1 * s, 11.1 * s, 7.6 * s, 12 * s, 1 * s)
      ..close();
    final paint = Paint()
      ..isAntiAlias = true
      ..shader = const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFF2B64C), Color(0xFF5FBF8E), Color(0xFF2F7A8A)],
        stops: [0, 0.45, 1],
      ).createShader(Offset.zero & size);
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_SparklePainter oldDelegate) => false;
}
