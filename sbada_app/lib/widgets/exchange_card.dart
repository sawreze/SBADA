import 'package:flutter/material.dart';

import '../models/exchange.dart';
import '../models/item.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import 'common.dart';
import 'net_image.dart';

/// 교환 요청 카드: [상대가 내놓은 물건] ⇄ [내 물건] + 수락/거절(받은 사람만)
class ExchangeCard extends StatelessWidget {
  const ExchangeCard({
    super.key,
    required this.exchange,
    required this.myId,
    this.busy = false,
    this.onAccept,
    this.onReject,
    this.onCancel,
    this.onOpenChat,
    this.compact = false,
  });

  final Exchange exchange;
  final int myId;
  final bool busy;
  final VoidCallback? onAccept;
  final VoidCallback? onReject;
  final VoidCallback? onCancel;
  final VoidCallback? onOpenChat;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final e = exchange;
    final receiver = e.isReceiver(myId);
    final statusColor = switch (e.status) {
      'ACCEPTED' => Sb.green,
      'REQUESTED' => Sb.mintText,
      _ => Sb.text3,
    };
    final headline = receiver
        ? '${e.requester.nickname}님이 교환을 원해요'
        : '${e.targetOwner.nickname}님에게 보낸 요청';
    return Container(
      margin: compact ? EdgeInsets.zero : const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Sb.card,
        borderRadius: Sb.r,
        border: Border.all(color: Sb.line),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(headline, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15))),
          Pill(e.statusLabel, color: statusColor.withValues(alpha: 0.12), textColor: statusColor),
        ]),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: _ItemSide(label: receiver ? '상대 물건' : '내 물건', item: e.offeredItem)),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 8),
            child: Icon(Icons.swap_horiz_rounded, color: Sb.mintText, size: 28),
          ),
          Expanded(child: _ItemSide(label: receiver ? '내 물건' : '상대 물건', item: e.targetItem)),
        ]),
        const SizedBox(height: 10),
        Text('AI 가격 차이 ${formatPercent(e.priceDiffPercent)} (내놓은 물건 기준)', style: sbCaption),
        if (e.message != null && e.message!.isNotEmpty) ...[
          const SizedBox(height: 8),
          Text('“${e.message}”', style: sbBody),
        ],
        if (e.closeReason != null && !e.isPending && e.status != 'ACCEPTED') ...[
          const SizedBox(height: 8),
          Text(e.closeReason!, style: sbCaption),
        ],
        if (e.isPending && receiver && (onAccept != null || onReject != null)) ...[
          const SizedBox(height: 14),
          Row(children: [
            Expanded(
              child: OutlinedButton(onPressed: busy ? null : onReject, child: const Text('거절')),
            ),
            const SizedBox(width: 10),
            Expanded(
              flex: 2,
              child: FilledButton(
                onPressed: busy ? null : onAccept,
                child: busy
                    ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                    : const Text('수락하고 교환하기'),
              ),
            ),
          ]),
        ],
        if (e.isPending && !receiver && onCancel != null) ...[
          const SizedBox(height: 12),
          OutlinedButton(onPressed: busy ? null : onCancel, child: const Text('요청 취소')),
        ],
        if (onOpenChat != null && e.chatRoomId != null) ...[
          const SizedBox(height: 4),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton.icon(
              onPressed: onOpenChat,
              icon: const Icon(Icons.chat_bubble_outline, size: 18),
              label: const Text('채팅으로 이야기하기'),
            ),
          ),
        ],
      ]),
    );
  }
}

class _ItemSide extends StatelessWidget {
  const _ItemSide({required this.label, required this.item});

  final String label;
  final ItemSummary item;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: sbCaption),
      const SizedBox(height: 4),
      AspectRatio(aspectRatio: 1, child: NetImage(item.imageUrl, radius: 12)),
      const SizedBox(height: 6),
      Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
      PriceText(item.price, size: 14),
    ]);
  }
}
