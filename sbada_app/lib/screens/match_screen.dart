import 'package:flutter/material.dart';

import '../models/item.dart';
import '../models/recommendation.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import '../widgets/net_image.dart';
import '../widgets/score_widgets.dart';
import 'item_detail_screen.dart';

/// 내 물건과 '비슷한 가격대'의 교환 후보(GET /api/items/{id}/matches)
/// 점수 = 0.40 가격 유사도 + 0.25 내 선호 + 0.20 상대 선호 + 0.10 신선도 + 0.05 상태 유사도
class MatchScreen extends StatefulWidget {
  const MatchScreen({super.key, required this.services, required this.item, this.justCreated = false});

  final AppServices services;
  final Item item;
  final bool justCreated;

  @override
  State<MatchScreen> createState() => _MatchScreenState();
}

class _MatchScreenState extends State<MatchScreen> {
  late Future<MatchResult> _future;
  final Set<int> _requested = {};
  int? _busyId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() => _future = widget.services.items.matches(widget.item.id);

  Future<void> _offer(Recommendation rec) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('바로 바꾸기'),
        content: Text("내 '${widget.item.title}'(${formatWon(widget.item.price)})로\n"
            "'${rec.item.title}'(${formatWon(rec.item.price)})을 요청할까요?\n\n"
            '${rec.item.owner.nickname}님이 수락하면 교환이 완료돼요.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('취소')),
          FilledButton(
            style: FilledButton.styleFrom(minimumSize: const Size(88, 44)),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('요청 보내기'),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    setState(() => _busyId = rec.item.id);
    try {
      final ex = await widget.services.exchanges.request(targetItemId: rec.item.id, offeredItemId: widget.item.id);
      if (!mounted) return;
      setState(() => _requested.add(rec.item.id));
      widget.services.itemsChanged.value++;
      showSnack(context, ex.status == 'ACCEPTED'
          ? '교환이 바로 성사되었어요! 🎉'
          : '교환 요청을 보냈어요! 상대가 수락하면 알림으로 알려 드려요 🔔');
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.justCreated ? '등록 완료!' : '비슷한 가격대 매칭')),
      body: FutureBuilder<MatchResult>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done) {
            return const LoadingView(message: '비슷한 가격대의 물건을 찾는 중…');
          }
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
          final result = snap.data!;
          return RefreshIndicator(
            onRefresh: () async {
              setState(_load);
              await _future;
            },
            child: ListView(children: [
              _header(result),
              // 올린 물건 카드 바로 아래 제목(사용자 요청)
              if (result.matches.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 6, 16, 2),
                  child: Text('비슷한 상품',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: Sb.navy)),
                ),
              if (result.matches.isEmpty)
                const Padding(
                  padding: EdgeInsets.only(top: 40),
                  child: EmptyView(
                    emoji: '🔎',
                    title: '아직 비슷한 가격대의 물건이 없어요',
                    message: '새 물건이 올라오면 알림으로 알려 드릴게요.',
                  ),
                ),
              for (final rec in result.matches) _matchTile(rec),
              const SizedBox(height: 32),
            ]),
          );
        },
      ),
    );
  }

  Widget _header(MatchResult r) {
    final base = r.base;
    return Container(
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(color: Sb.bg, borderRadius: Sb.r),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (widget.justCreated) ...[
          Text('🎉 물건이 올라갔어요', style: sbH2),
          const SizedBox(height: 4),
          Text('이 물건과 바꿀 만한 후보를 찾아봤어요.', style: sbBody),
          const SizedBox(height: 14),
        ],
        Row(children: [
          NetImage(base.firstImage, size: 64, radius: 12),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(base.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
              PriceText(base.price, size: 18),
            ]),
          ),
        ]),
        const SizedBox(height: 12),
        Text(
          '가격 ${formatShortWon(base.price ~/ 2)} ~ ${formatShortWon(base.price * 2)} 사이 후보 ${r.candidates}개를 '
          '점수순으로 골랐어요. 점수를 누르면 계산 과정을 볼 수 있어요.',
          style: sbCaption,
        ),
      ]),
    );
  }

  Widget _matchTile(Recommendation rec) {
    final requested = rec.requestStatus == 'REQUESTED' || _requested.contains(rec.item.id);
    return Column(children: [
      ItemTile(
        item: rec.item,
        onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
            builder: (_) => ItemDetailScreen(services: widget.services, itemId: rec.item.id))),
        trailing: GestureDetector(
          onTap: () => showScoreSheet(context, rec, title: '매칭 점수 ${rec.score}점'),
          child: ScoreBadge(rec.score),
        ),
        footer: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          ReasonChips(rec.reasons),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerLeft,
            child: requested
                ? Pill('요청 보냄 · 수락 대기', color: Sb.greenSoft, textColor: Sb.green, icon: Icons.schedule)
                : FilledButton.tonalIcon(
                    style: FilledButton.styleFrom(minimumSize: const Size(0, 36)),
                    onPressed: _busyId == null ? () => _offer(rec) : null,
                    icon: const Icon(Icons.swap_horiz_rounded, size: 18),
                    label: Text("'${_short(widget.item.title)}'로 바로 바꾸기"),
                  ),
          ),
        ]),
      ),
      const Divider(height: 1, indent: 20, endIndent: 20),
    ]);
  }

  String _short(String s) => s.length <= 8 ? s : '${s.substring(0, 8)}…';
}
