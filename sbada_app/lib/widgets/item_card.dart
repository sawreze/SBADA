import 'package:flutter/material.dart';

import '../models/item.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import 'common.dart';
import 'net_image.dart';

/// 목록의 물건 한 줄(디자인 '홈' 시안):
///   [사진 96dp] 제목(2줄)           (점수)
///               동네 · 시간
///               AI 160,000원
///               (추천 이유 칩)          ⇄ 요청수  👁 조회수
class ItemTile extends StatelessWidget {
  const ItemTile({super.key, required this.item, this.onTap, this.trailing, this.footer});

  final Item item;
  final VoidCallback? onTap;
  final Widget? trailing;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final dong = item.owner.region.split(' ').last;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Stack(children: [
            NetImage(item.firstImage, size: 96, radius: 14),
            if (!item.isAvailable)
              Positioned.fill(
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.55),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  alignment: Alignment.center,
                  child: Text(item.isHeld ? '보류' : '교환완료', style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700)),
                ),
              ),
          ]),
          const SizedBox(width: 14),
          Expanded(
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 96),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Expanded(
                    child: Text(item.title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: Sb.navy, height: 1.35)),
                  ),
                  if (trailing != null) ...[const SizedBox(width: 8), trailing!],
                ]),
                const SizedBox(height: 4),
                Text('$dong · ${timeAgo(item.createdAt)}',
                    style: sbCaption, maxLines: 1, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 4),
                PriceText(item.price), // '12,000원 AI 분석 가치'

                // 정든 물건에게 보내는 메시지가 있으면 가격 아래 칩(추천 이유 등) 대신 "메시지" 를 굵게
                if (item.farewellMessage != null && item.farewellMessage!.isNotEmpty) ...[
                  const SizedBox(height: 6),
                  Text('"${item.farewellMessage}"',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 14, fontWeight: FontWeight.w800, color: Sb.navy, height: 1.4)),
                ] else if (footer != null) ...[const SizedBox(height: 6), footer!],
                const SizedBox(height: 4),
                Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                  if (item.requestCount > 0) ...[
                    Icon(Icons.swap_horiz_rounded, size: 15, color: Sb.text3),
                    const SizedBox(width: 2),
                    Text('${item.requestCount}', style: sbCaption),
                    const SizedBox(width: 10),
                  ],
                  if (item.viewCount > 0) ...[
                    Icon(Icons.remove_red_eye_outlined, size: 15, color: Sb.text3),
                    const SizedBox(width: 3),
                    Text('${item.viewCount}', style: sbCaption),
                    const SizedBox(width: 10),
                  ],
                  // 찜(하트) 수: 0 이어도 자리를 보여 준다(눈 옆)
                  Icon(Icons.favorite_border_rounded, size: 15, color: Sb.text3, semanticLabel: '찜'),
                  const SizedBox(width: 3),
                  Text('${item.likeCount}', style: sbCaption),
                ]),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}

/// AI 가격 범위 막대: 최소 ── ● 추정가 ── 최대
class PriceRangeBar extends StatelessWidget {
  const PriceRangeBar({super.key, required this.price, required this.min, required this.max});

  final int price;
  final int min;
  final int max;

  @override
  Widget build(BuildContext context) {
    final span = (max - min).abs() < 1 ? 1 : (max - min);
    final t = ((price - min) / span).clamp(0.0, 1.0);
    return Column(children: [
      LayoutBuilder(builder: (context, c) {
        return SizedBox(
          height: 18,
          child: Stack(alignment: Alignment.centerLeft, children: [
            Container(
              height: 6,
              decoration: BoxDecoration(
                gradient: LinearGradient(colors: [Sb.blueSoft, Sb.blue, Sb.blueSoft]),
                borderRadius: BorderRadius.circular(99),
              ),
            ),
            Positioned(
              left: (c.maxWidth - 18) * t,
              child: Container(
                width: 18,
                height: 18,
                decoration: BoxDecoration(
                  color: Sb.base,
                  shape: BoxShape.circle,
                  border: Border.all(color: Sb.mintText, width: 4),
                ),
              ),
            ),
          ]),
        );
      }),
      const SizedBox(height: 6),
      Row(children: [
        Text(formatWon(min), style: sbCaption),
        const Spacer(),
        Text(formatWon(max), style: sbCaption),
      ]),
    ]);
  }
}
