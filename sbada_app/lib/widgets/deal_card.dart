import 'package:flutter/material.dart';

import '../models/exchange.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import 'common.dart';
import 'deal_sheets.dart';

// 2026-11-25 사용자 요청: 채팅방의 '거래 카드'
//   추가요금 요청(수락 · 거절) → 결제 대기(결제하기) → 운송장 입력(7일) → 배송 현황 → 거래 완료(후기)
//   · 기간 만료(거래 취소 + 전액 환불)
// 한 교환에 카드 메시지가 여러 장 생겨도, 맨 마지막 한 장만 이 카드로 그리고 나머지는 한 줄 안내로 그린다.

/// 이 교환이 거래 카드로 보여 줄 단계인가(추가요금 요청이 있었거나, 배송 단계에 들어감)
bool hasDealFlow(Exchange e) => e.extraRequestStatus != 'NONE' || e.dealStage != 'NONE';

class DealCard extends StatefulWidget {
  const DealCard({super.key, required this.services, required this.exchange, required this.onChanged});

  final AppServices services;
  final Exchange exchange;

  /// 서버가 돌려준 바뀐 교환(채팅방이 같은 교환의 카드들을 한꺼번에 바꾼다)
  final ValueChanged<Exchange> onChanged;

  @override
  State<DealCard> createState() => _DealCardState();
}

class _DealCardState extends State<DealCard> {
  bool _busy = false;

  Exchange get e => widget.exchange;
  int get _myId => widget.services.session.user?.id ?? -1;
  bool get _receiver => e.isReceiver(_myId);
  String get _otherName => _receiver ? e.requester.nickname : e.targetOwner.nickname;

  Future<void> _run(Future<Exchange?> Function() action, {String? done}) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final updated = await action();
      if (updated != null) {
        widget.onChanged(updated);
        widget.services.itemsChanged.value++;
        if (done != null && mounted) showSnack(context, done);
      }
    } on ApiException catch (err) {
      if (mounted) showSnack(context, err.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final (icon, title, color) = _head();
    return Container(
      key: ValueKey('deal-card-${e.id}'),
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
      decoration: BoxDecoration(
        color: Sb.card,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.35)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Row(children: [
          Icon(icon, color: color, size: 22),
          const SizedBox(width: 8),
          Expanded(child: Text(title, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Sb.navy))),
          Pill(e.statusLabel, color: color.withValues(alpha: 0.12), textColor: color),
        ]),
        const SizedBox(height: 6),
        Text('${e.offeredItem.title} ⇄ ${e.targetItem.title}',
            maxLines: 1, overflow: TextOverflow.ellipsis, style: sbCaption),
        const SizedBox(height: 10),
        ..._body(),
      ]),
    );
  }

  (IconData, String, Color) _head() {
    if (e.status == 'CANCELED' && e.dealStage == 'EXPIRED') return (Icons.timer_off_rounded, '기간이 만료되었습니다', Sb.red);
    if (e.dealStage == 'COMPLETED') return (Icons.verified_rounded, '거래 완료', Sb.green);
    if (e.dealStage == 'SHIPPING') return (Icons.local_shipping_rounded, '배송 · 운송장', Sb.mintText);
    if (e.status == 'REJECTED' || e.status == 'CANCELED') return (Icons.block_rounded, '거래가 끝났어요', Sb.text3);
    return (Icons.add_card_rounded, '추가요금', Sb.mintText);
  }

  List<Widget> _body() {
    final s = widget.services;
    // ── 기간 만료: 거래 취소 + 전액 환불 ──
    if (e.status == 'CANCELED' && e.dealStage == 'EXPIRED') {
      return [
        Text('7일 안에 두 사람의 운송장이 모두 입력되지 않아 거래가 취소되었어요.'
            '${e.extraAmount > 0 ? ' 결제한 추가요금은 전액 환불돼요.' : ''} 물건은 다시 교환할 수 있는 상태로 돌아갔어요.',
            style: sbBody),
      ];
    }
    if (e.status == 'REJECTED' || e.status == 'CANCELED') {
      return [Text(e.closeReason ?? '이 교환은 더 진행되지 않아요.', style: sbBody)];
    }
    // ── 거래 완료: 후기 ──
    if (e.dealStage == 'COMPLETED') {
      return [
        Text('두 물건이 모두 도착했어요.${e.extraAmount > 0 ? ' 보관 중이던 추가요금 ${formatWon(e.extraAmount)}이 전달되었어요.' : ''}',
            style: sbBody),
        const SizedBox(height: 12),
        if (!e.iReviewed)
          FilledButton.icon(
            key: ValueKey('deal-review-${e.id}'),
            onPressed: _busy
                ? null
                : () async {
                    final ok = await showReviewSheet(context, s, e, _otherName);
                    if (!ok || !mounted) return;
                    showSnack(context, '후기를 남겼어요. 고마워요!');
                    await _run(() => s.exchanges.get(e.id));
                  },
            icon: const Icon(Icons.rate_review_rounded),
            label: Text('$_otherName님 거래 후기 쓰기'),
          )
        else
          Text('후기를 남겼어요.${e.otherReviewed ? ' 상대도 후기를 남겼어요.' : ''}', style: sbCaption),
      ];
    }
    // ── 배송: 운송장 두 장 + 마감 ──
    if (e.dealStage == 'SHIPPING') {
      final mine = e.myShipment;
      final other = e.otherShipment;
      return [
        if (e.shipDeadline != null && (mine == null || other == null))
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Text('운송장 입력 마감 ${formatShipDeadline(e.shipDeadline!)} — 지나면 거래가 취소되고 전액 환불돼요.',
                style: TextStyle(fontSize: 13, color: Sb.red, height: 1.4)),
          ),
        _shipRow('내가 보낸 물건', mine),
        const SizedBox(height: 8),
        _shipRow('$_otherName님이 보낸 물건', other),
        const SizedBox(height: 12),
        if (mine == null)
          FilledButton.icon(
            key: ValueKey('deal-ship-${e.id}'),
            onPressed: _busy ? null : () => _run(() => showShipmentSheet(context, s, e), done: '운송장을 등록했어요. 배송 현황이 채팅과 알림으로 와요.'),
            icon: const Icon(Icons.edit_note_rounded),
            label: const Text('운송장 번호 입력'),
          )
        else
          OutlinedButton.icon(
            key: ValueKey('deal-refresh-${e.id}'),
            onPressed: _busy ? null : () => _run(() => s.exchanges.refreshShipment(e.id)),
            icon: const Icon(Icons.refresh_rounded),
            label: const Text('배송 현황 새로고침'),
          ),
      ];
    }
    // ── 교환 대기 중: 추가요금 요청 · 결제 대기 · 거절됨 ──
    switch (e.extraRequestStatus) {
      case 'REQUESTED':
        return [
          Text(
              _receiver
                  ? '추가요금 ${formatWon(e.extraAmount)}을 요청했어요. $_otherName님의 답을 기다리는 중이에요.'
                  : '$_otherName님이 추가요금 ${formatWon(e.extraAmount)}을 요청했어요. 수락하면 결제 뒤 교환이 진행돼요.',
              style: sbBody),
          if (!_receiver) ...[
            const SizedBox(height: 12),
            Row(children: [
              Expanded(
                child: OutlinedButton(
                  key: ValueKey('deal-decline-${e.id}'),
                  onPressed: _busy ? null : () => _run(() => s.exchanges.declineExtraFee(e.id), done: '추가요금 요청을 거절했어요.'),
                  child: const Text('거절'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: FilledButton(
                  key: ValueKey('deal-agree-${e.id}'),
                  onPressed: _busy
                      ? null
                      : () async {
                          await _run(() => s.exchanges.agreeExtraFee(e.id));
                          if (!mounted) return;
                          // 수락하면 바로 결제 창
                          final fresh = await s.exchanges.get(e.id).catchError((_) => e);
                          if (mounted) await showDealPaySheet(context, s, fresh);
                        },
                  child: const Text('수락하고 결제'),
                ),
              ),
            ]),
          ],
        ];
      case 'AGREED':
        final paid = e.paymentStatus == 'PAID_HELD';
        return [
          Text(
              paid
                  ? '결제가 확인되었어요. 곧 교환이 진행돼요.'
                  : _receiver
                      ? '$_otherName님이 추가요금 ${formatWon(e.extraAmount)}을 수락했어요. 결제가 확인되면 교환이 진행돼요.'
                      : '추가요금 ${formatWon(e.extraAmount)}을 결제해 주세요. 돈은 두 물건이 모두 도착할 때까지 쓰바다가 보관해요.',
              style: sbBody),
          if (!_receiver && !paid) ...[
            const SizedBox(height: 12),
            FilledButton.icon(
              key: ValueKey('deal-pay-${e.id}'),
              onPressed: _busy ? null : () => showDealPaySheet(context, s, e),
              icon: const Icon(Icons.payments_rounded),
              label: Text('${formatWon(e.extraAmount)} 결제하기'),
            ),
          ],
        ];
      case 'DECLINED':
        return [
          Text(
              _receiver
                  ? '$_otherName님이 추가요금 요청을 거절했어요. 바로 수락 · 다시 요청 · 거절 중에서 골라 주세요.'
                  : '추가요금 요청을 거절했어요. $_otherName님이 다시 고르는 중이에요.',
              style: sbBody),
          if (_receiver) ...[
            const SizedBox(height: 12),
            Row(children: [
              Expanded(
                child: OutlinedButton(
                  key: ValueKey('deal-reject-${e.id}'),
                  onPressed: _busy ? null : () => _run(() => s.exchanges.reject(e.id), done: '교환을 거절했어요.'),
                  child: const Text('거절'),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: FilledButton(
                  key: ValueKey('deal-rechoose-${e.id}'),
                  onPressed: _busy ? null : () => _run(() => showAcceptChoice(context, s, e)),
                  child: const Text('다시 선택'),
                ),
              ),
            ]),
          ],
        ];
    }
    return const [];
  }

  Widget _shipRow(String label, Shipment? sh) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: Sb.bg, borderRadius: BorderRadius.circular(14)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w700))),
          if (sh != null)
            Pill(sh.levelLabel,
                color: (sh.delivered ? Sb.green : Sb.blue).withValues(alpha: 0.12),
                textColor: sh.delivered ? Sb.green : Sb.mintText),
        ]),
        const SizedBox(height: 4),
        if (sh == null)
          Text('아직 운송장이 없어요', style: sbCaption)
        else ...[
          Text('${sh.carrierName} ${sh.invoiceMasked}', style: sbCaption),
          const SizedBox(height: 8),
          _Progress(level: sh.level),
          if (sh.lastDetail != null) ...[
            const SizedBox(height: 6),
            Text([sh.lastTime, sh.lastWhere, sh.lastDetail].whereType<String>().join(' · '),
                maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 12, color: Sb.text2)),
          ],
        ],
      ]),
    );
  }
}

/// 배송 단계 막대(1 접수 · 2 집화 · 3 배송중 · 4 지점 도착 · 5 배송 출발 · 6 배송 완료)
class _Progress extends StatelessWidget {
  const _Progress({required this.level});

  final int level;

  @override
  Widget build(BuildContext context) {
    return Row(children: [
      for (var i = 1; i <= 6; i++) ...[
        Expanded(
          child: Container(
            height: 6,
            decoration: BoxDecoration(
              color: i <= level ? (level >= 6 ? Sb.green : Sb.blue) : Sb.line,
              borderRadius: BorderRadius.circular(3),
            ),
          ),
        ),
        if (i < 6) const SizedBox(width: 3),
      ],
    ]);
  }
}
