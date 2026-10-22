import 'package:flutter/material.dart';

import '../models/item.dart';
import '../theme/app_theme.dart';

/// 물건 상세 '거래 희망 장소' 지도 바로 위: AI 가 사진 · 글을 보고 쓴 2~3문장 요약(GET /api/items/{id}/summary).
/// 처음 보는 물건은 Gemma 가 쓰는 동안(30초 안팎) '요약하고 있어요'를 보여 주고, 서버가 저장해 두므로 다음부터는 바로 뜬다.
/// 실패하면 칸을 숨긴다(상세 화면의 다른 내용은 그대로).
class AiSummaryCard extends StatefulWidget {
  const AiSummaryCard({super.key, required this.load});

  /// 요약 불러오기(ItemRepository.summary). 테스트에서 가짜를 넣을 수 있게 함수로 받는다
  final Future<AiItemSummary> Function() load;

  @override
  State<AiSummaryCard> createState() => _AiSummaryCardState();
}

class _AiSummaryCardState extends State<AiSummaryCard> {
  late final Future<AiItemSummary> _future = widget.load();

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<AiItemSummary>(
      future: _future,
      builder: (context, snap) {
        if (snap.hasError) return const SizedBox.shrink();
        final s = snap.data;
        if (snap.connectionState == ConnectionState.done && (s == null || s.summary.trim().isEmpty)) {
          return const SizedBox.shrink();
        }
        return Padding(
          padding: const EdgeInsets.only(top: 20),
          child: Container(
            key: const ValueKey('ai-summary'),
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Sb.blue.withValues(alpha: Sb.isDark ? 0.10 : 0.16),
              borderRadius: Sb.r,
              border: Border.all(color: Sb.mintText.withValues(alpha: 0.35)),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Icon(Icons.auto_awesome, size: 16, color: Sb.mintText),
                const SizedBox(width: 6),
                Text(s == null || s.byAi ? 'AI 상품 요약' : '상품 요약',
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: Sb.mintText)),
                if (s != null && s.byAi) ...[
                  const SizedBox(width: 6),
                  Text('Gemma', style: TextStyle(fontSize: 12, color: Sb.text3)),
                ],
              ]),
              const SizedBox(height: 8),
              if (s == null)
                Row(children: [
                  SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2, color: Sb.mintText)),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text('AI가 이 물건을 요약하고 있어요(처음 한 번은 30초쯤 걸려요)',
                        style: TextStyle(fontSize: 13, color: Sb.text2)),
                  ),
                ])
              else
                Text(s.summary, style: TextStyle(fontSize: 15, height: 1.55, color: Sb.navy)),
            ]),
          ),
        );
      },
    );
  }
}
