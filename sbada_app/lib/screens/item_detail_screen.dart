import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models/item.dart';
import '../models/recommendation.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/ai_summary_card.dart';
import '../widgets/common.dart';
import '../widgets/glass.dart';
import '../widgets/item_card.dart';
import '../widgets/like_heart.dart';
import '../widgets/score_widgets.dart';
import '../widgets/my_item_picker.dart';
import '../widgets/net_image.dart';
import 'chat_room_screen.dart';
import 'likes_screen.dart';
import 'match_screen.dart';
import 'place_map_screen.dart';

class ItemDetailScreen extends StatefulWidget {
  const ItemDetailScreen({super.key, required this.services, required this.itemId});

  final AppServices services;
  final int itemId;

  @override
  State<ItemDetailScreen> createState() => _ItemDetailScreenState();
}

/// 상세 화면의 '내 ○○와 매칭 N점' 카드에 쓰는 값: 내 물건 + 그 물건 기준 매칭 결과에서 찾은 이 물건의 점수
typedef _MyMatch = ({Item mine, Recommendation rec});

class _ItemDetailScreenState extends State<ItemDetailScreen> {
  late Future<Item> _future;
  Future<_MyMatch?>? _match;
  Future<List<Item>>? _similar;
  bool _busy = false;
  int _page = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = widget.services.items.detail(widget.itemId);
    _match = _future.then(_findMatch).catchError((Object _) => null);
    _similar = _future.then(_findSimilar).catchError((Object _) => <Item>[]);
  }

  /// '보고 있는 물품과 비슷한 물품': 같은 종류를 먼저, 그다음 가격이 가까운 순(가격 비율의 로그 차이)으로 6개.
  /// 이 물건 · 내 물건 · 교환이 끝난 물건은 뺀다. 기존 목록 API(GET /api/items)를 그대로 쓴다.
  Future<List<Item>> _findSimilar(Item item) async {
    final me = widget.services.session.user?.id;
    final all = await widget.services.items.latest();
    final list = all.where((i) => i.id != item.id && i.isAvailable && i.owner.id != me).toList();
    double gap(Item i) => (math.log(math.max(i.price, 1) / math.max(item.price, 1))).abs();
    list.sort((a, b) {
      final ca = a.category == item.category ? 0 : 1;
      final cb = b.category == item.category ? 0 : 1;
      if (ca != cb) return ca.compareTo(cb);
      return gap(a).compareTo(gap(b));
    });
    return list.take(6).toList();
  }

  Future<void> _openOther(Item other) async {
    await Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => ItemDetailScreen(services: widget.services, itemId: other.id)));
  }

  bool _isMine(Item item) => item.owner.id == widget.services.session.user?.id;

  /// 디자인 시안(상세): 내 물건 중 이 물건과 가격이 가장 가까운 것을 골라
  /// 기존 매칭 API(GET /api/items/{내 물건}/matches)의 점수·요소를 그대로 보여 준다. 후보에 없으면 카드를 숨긴다.
  Future<_MyMatch?> _findMatch(Item item) async {
    final s = widget.services;
    if (!s.session.isLoggedIn || _isMine(item) || !item.isAvailable) return null;
    final mine = (await s.items.mine()).where((i) => i.isAvailable).toList();
    if (mine.isEmpty) return null;
    mine.sort((a, b) => (a.price - item.price).abs().compareTo((b.price - item.price).abs()));
    final best = mine.first;
    final result = await s.items.matches(best.id, limit: 50);
    for (final r in result.matches) {
      if (r.item.id == item.id) return (mine: best, rec: r);
    }
    return null;
  }

  /// '바로 바꾸기': 내 물건을 고르면 상대에게 알림이 가고, 상대가 수락하면 교환 완료
  Future<void> _quickExchange(Item item) async {
    final choice = await pickMyItem(context, widget.services, target: item);
    if (choice == null || !mounted) return;
    setState(() => _busy = true);
    try {
      await widget.services.exchanges
          .request(targetItemId: item.id, offeredItemId: choice.item.id, message: choice.message);
      if (!mounted) return;
      widget.services.itemsChanged.value++;
      showSnack(context, '교환 요청을 보냈어요! ${item.owner.nickname}님이 수락하면 교환이 완료돼요 🔔');
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chat(Item item) async {
    setState(() => _busy = true);
    try {
      final room = await widget.services.chats.open(item.id);
      if (!mounted) return;
      await Navigator.of(context).push(MaterialPageRoute<void>(
          builder: (_) => ChatRoomScreen(services: widget.services, roomId: room.id)));
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete(Item item) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('물건을 내릴까요?'),
        content: const Text('이 물건에 온 교환 요청은 모두 취소돼요.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('아니요')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('내리기')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await widget.services.items.delete(item.id);
      widget.services.itemsChanged.value++;
      if (mounted) Navigator.of(context).pop();
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<Item>(
      future: _future,
      builder: (context, snap) {
        if (snap.connectionState != ConnectionState.done) {
          return Scaffold(appBar: AppBar(), body: const LoadingView());
        }
        if (snap.hasError) {
          return Scaffold(appBar: AppBar(), body: ErrorView(error: snap.error!, onRetry: () => setState(_load)));
        }
        final item = snap.data!;
        return Scaffold(
          body: CustomScrollView(slivers: [
            // 디자인 시안: 사진 300dp + 왼쪽 위 유리 뒤로가기 버튼
            SliverAppBar(
              pinned: true,
              expandedHeight: 300,
              automaticallyImplyLeading: false,
              leadingWidth: 72,
              leading: Padding(
                padding: const EdgeInsets.only(left: 12),
                child: Center(
                  child: GlassIconButton(
                    icon: Icons.arrow_back_rounded,
                    tooltip: '뒤로',
                    onPressed: () => Navigator.of(context).maybePop(),
                  ),
                ),
              ),
              flexibleSpace: FlexibleSpaceBar(background: _gallery(item)),
            ),
            SliverToBoxAdapter(child: _content(item)),
            SliverToBoxAdapter(child: _similarSection()),
          ]),
          bottomNavigationBar: SafeArea(child: _actions(item)),
        );
      },
    );
  }

  Widget _gallery(Item item) {
    final urls = item.imageUrls;
    return Stack(children: [
      PageView.builder(
        itemCount: urls.isEmpty ? 1 : urls.length,
        onPageChanged: (p) => setState(() => _page = p),
        itemBuilder: (context, i) => NetImage(urls.isEmpty ? null : urls[i], radius: 0),
      ),
      if (urls.length > 1)
        Positioned(
          bottom: 14,
          left: 0,
          right: 0,
          child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            for (var i = 0; i < urls.length; i++)
              Container(
                width: 7,
                height: 7,
                margin: const EdgeInsets.symmetric(horizontal: 3),
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: i == _page ? Colors.white : Colors.white54,
                ),
              ),
          ]),
        ),
    ]);
  }

  Widget _content(Item item) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 20),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // 보류(콘텐츠 검사에 걸림): 주인에게만 보이는 안내
        if (item.isHeld)
          Container(
            margin: const EdgeInsets.only(bottom: 14),
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: Sb.red.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: Sb.red.withValues(alpha: 0.4)),
            ),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(Icons.block_rounded, color: Sb.red, size: 22),
              const SizedBox(width: 10),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('보류된 물건이에요', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: Sb.red)),
                  const SizedBox(height: 4),
                  Text('${item.moderationReason ?? '콘텐츠 검사에 걸렸어요.'}\n다른 사람에게는 보이지 않아요. 내린 뒤 내용을 고쳐 다시 올려 주세요.',
                      style: TextStyle(fontSize: 13, height: 1.5, color: Sb.text2)),
                ]),
              ),
            ]),
          ),
        Row(children: [
          Container(
            width: 40,
            height: 40,
            alignment: Alignment.center,
            decoration: BoxDecoration(shape: BoxShape.circle, color: Sb.blueSoft),
            child: Text(item.owner.nickname.characters.first,
                style: TextStyle(color: Sb.mintText, fontWeight: FontWeight.w800)),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(item.owner.nickname, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Sb.navy)),
              Text(item.owner.region, style: TextStyle(fontSize: 12, color: Sb.text3)),
            ]),
          ),
          if (!item.isAvailable) Pill('교환 완료', color: Sb.greenSoft, textColor: Sb.green),
        ]),
        const SizedBox(height: 12),
        Divider(height: 1, color: Sb.line),
        const SizedBox(height: 12),
        Text(item.title, style: TextStyle(fontSize: 21, fontWeight: FontWeight.w800, color: Sb.navy)),
        const SizedBox(height: 4),
        Text('${item.categoryLabel} · ${item.conditionLabel} · ${timeAgo(item.createdAt)}',
            style: TextStyle(fontSize: 13, color: Sb.text3)),
        const SizedBox(height: 8),
        PriceText(item.price, size: 22),
        FutureBuilder<_MyMatch?>(
          future: _match,
          builder: (context, snap) {
            final m = snap.data;
            if (m == null) return const SizedBox.shrink();
            return Padding(padding: const EdgeInsets.only(top: 12), child: _matchCard(m));
          },
        ),
        // 시연용 표본 물건(가격을 미리 넣어 둔 물건)은 AI 분석 카드를 보여 주지 않는다. 실제 Gemma 분석 물건만 근거 카드를 보여 준다.
        if (item.aiProvider != 'seed') ...[
          const SizedBox(height: 12),
          _aiCard(item),
        ],
        if (item.description != null) ...[
          const SizedBox(height: 20),
          Text(item.description!, style: TextStyle(fontSize: 16, height: 1.6, color: Sb.navy)),
        ],
        if (item.wishCategoryLabels.isNotEmpty) ...[
          const SizedBox(height: 20),
          const Text('이런 물건과 바꾸고 싶어요', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final w in item.wishCategoryLabels) Pill(w, color: Sb.blueSoft, textColor: Sb.mintText),
          ]),
        ],
        // 지도 바로 위: AI 가 사진 · 글을 보고 쓴 요약(2026-10-22 사용자 요청). 실패하면 칸이 숨는다
        AiSummaryCard(key: ValueKey('summary-${item.id}'), load: () => widget.services.items.summary(item.id)),
        const SizedBox(height: 20),
        TradePlaceCard(services: widget.services, item: item),
        const SizedBox(height: 16),
        Text('조회 ${item.viewCount} · 교환 요청 ${item.requestCount}', style: sbCaption),
      ]),
    );
  }

  /// 상세 화면 맨 아래: 보고 있는 물품과 비슷한 물품(2열, 사진 · 이름 · 가격)
  Widget _similarSection() {
    return FutureBuilder<List<Item>>(
      future: _similar,
      builder: (context, snap) {
        final list = snap.data ?? const <Item>[];
        if (list.isEmpty) return const SizedBox(height: 12);
        return Container(
          decoration: BoxDecoration(border: Border(top: BorderSide(color: Sb.line, width: 8))),
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 24),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('보고 있는 물품과 비슷한 물품', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: Sb.navy)),
            const SizedBox(height: 14),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              padding: EdgeInsets.zero,
              itemCount: list.length,
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                crossAxisSpacing: 12,
                mainAxisSpacing: 18,
                childAspectRatio: 0.74,
              ),
              itemBuilder: (context, i) => _similarCard(list[i]),
            ),
          ]),
        );
      },
    );
  }

  Widget _similarCard(Item it) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () => _openOther(it),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        AspectRatio(aspectRatio: 1.33, child: NetImage(it.firstImage, radius: 12)),
        const SizedBox(height: 8),
        Text(it.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 15, fontWeight: FontWeight.w500, color: Sb.navy)),
        const SizedBox(height: 2),
        PriceText(it.price),
        const SizedBox(height: 2),
        Text('${it.owner.region.split(' ').last} · ${timeAgo(it.createdAt)}',
            maxLines: 1, overflow: TextOverflow.ellipsis, style: sbCaption),
      ]),
    );
  }

  /// 디자인 시안의 '매칭 점수' 카드: 내 ○○와 매칭 N점 + 요소별 막대(가격 40% · 내 취향 25% · 서로 원함 20% · 신선도 10% · 상태 5%)
  Widget _matchCard(_MyMatch m) {
    const short = {'price': '가격', 'want': '내 취향', 'mutual': '서로 원함', 'fresh': '신선도', 'condition': '상태'};
    return InkWell(
      borderRadius: BorderRadius.circular(18),
      onTap: () => showScoreSheet(context, m.rec, title: '내 ${m.mine.title}와 매칭'),
      child: Container(
        padding: const EdgeInsets.all(16),
        // 다크: 카드색(#1A1C20) / 화이트: 카드색이 바탕과 같은 흰색이라 옅은 회색(bg)으로 구분
        decoration: BoxDecoration(color: Sb.isDark ? Sb.card : Sb.bg, borderRadius: BorderRadius.circular(18)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('내 ${m.mine.title}와 매칭',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Sb.navy)),
                const SizedBox(height: 2),
                Text('눌러서 계산 보기', style: TextStyle(fontSize: 12, color: Sb.text3)),
              ]),
            ),
            const SizedBox(width: 12),
            ScoreBadge(m.rec.score, size: 72, duration: const Duration(milliseconds: 1100)),
          ]),
          const SizedBox(height: 8),
          for (var i = 0; i < m.rec.factors.length; i++)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 3.5),
              child: Row(children: [
                SizedBox(
                  width: 86,
                  child: Text(
                      '${short[m.rec.factors[i].key] ?? m.rec.factors[i].label} ${(m.rec.factors[i].weight * 100).round()}%',
                      style: TextStyle(fontSize: 13, color: Sb.text2)),
                ),
                Expanded(
                  child: AnimatedBar(value: m.rec.factors[i].value, delay: Duration(milliseconds: 150 * i)),
                ),
              ]),
            ),
        ]),
      ),
    );
  }

  /// AI 가격 근거(물건 올리기 시안의 AI 카드와 같은 모양: 옅은 민트 바탕 + 민트 테두리)
  Widget _aiCard(Item item) {
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Sb.blue.withValues(alpha: Sb.isDark ? 0.10 : 0.16),
        borderRadius: Sb.r,
        border: Border.all(color: Sb.mintText.withValues(alpha: 0.35)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(Icons.auto_awesome, size: 16, color: Sb.mintText),
          const SizedBox(width: 6),
          Expanded(
            child: Text('${_providerLabel(item.aiProvider)} · ${item.categoryLabel}',
                style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Sb.mintText)),
          ),
        ]),
        const SizedBox(height: 8),
        Text('적정 범위 ${formatWon(item.priceMin)} ~ ${formatWon(item.priceMax)} · 신뢰도 ${(item.aiConfidence * 100).round()}%',
            style: TextStyle(fontSize: 13, color: Sb.text2)),
        const SizedBox(height: 12),
        PriceRangeBar(price: item.price, min: item.priceMin, max: item.priceMax),
        if (item.aiReason != null) ...[
          const SizedBox(height: 10),
          Text(item.aiReason!, style: TextStyle(fontSize: 14, height: 1.5, color: Sb.text2)),
        ],
      ]),
    );
  }

  String _providerLabel(String? p) => switch (p) {
        'google' => 'Gemma AI 가격 분석',
        'ollama' => 'Gemma AI 가격 분석(로컬)',
        'seed' => 'AI 가격 분석',
        _ => '기준가 추정(AI 미연결)',
      };

  Widget _actions(Item item) {
    final padding = const EdgeInsets.fromLTRB(16, 8, 16, 12);
    if (_isMine(item)) {
      return Padding(
        padding: padding,
        child: Row(children: [
          Expanded(
            child: OutlinedButton(
                onPressed: item.isAvailable || item.isHeld ? () => _delete(item) : null, child: const Text('내리기')),
          ),
          const SizedBox(width: 10),
          Expanded(
            flex: 2,
            child: FilledButton.icon(
              onPressed: item.isAvailable
                  ? () => Navigator.of(context).push(MaterialPageRoute<void>(
                      builder: (_) => MatchScreen(services: widget.services, item: item)))
                  : null,
              style: FilledButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 12)),
              icon: const Icon(Icons.balance, size: 20),
              label: const Text('비슷한 가격대 매칭', maxLines: 1, softWrap: false),
            ),
          ),
        ]),
      );
    }
    if (!item.isAvailable) {
      return Padding(
        padding: padding,
        child: const FilledButton(onPressed: null, child: Text('교환이 끝난 물건이에요')),
      );
    }
    // 버튼 글자가 두 줄로 꺾이지 않도록 폭을 5:6 으로 나누고 안쪽 여백을 줄인다
    const tight = EdgeInsets.symmetric(horizontal: 12);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Sb.base,
        border: Border(top: BorderSide(color: Sb.line)),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(8, 12, 16, 12),
        child: Row(children: [
          // 왼쪽 아래 찜(하트): 누르면 통통 튀고, 아래에서 '관심목록에 추가했어요' 팝업이 올라온다
          LikeHeartButton(
            services: widget.services,
            itemId: item.id,
            onOpenList: () => Navigator.of(context)
                .push(MaterialPageRoute<void>(builder: (_) => LikesScreen(services: widget.services))),
          ),
          const SizedBox(width: 4),
          Expanded(
            flex: 5,
            child: OutlinedButton(
              style: OutlinedButton.styleFrom(padding: tight),
              onPressed: _busy ? null : () => _chat(item),
              child: const Text('채팅하기', maxLines: 1, softWrap: false),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            flex: 6,
            child: FilledButton.icon(
              style: FilledButton.styleFrom(padding: tight),
              onPressed: _busy ? null : () => _quickExchange(item),
              icon: const Icon(Icons.swap_horiz_rounded, size: 22),
              label: const Text('바로 바꾸기', maxLines: 1, softWrap: false),
            ),
          ),
        ]),
      ),
    );
  }
}
