import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';

import '../models/category.dart';
import '../models/item.dart';
import '../models/recommendation.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/area_scope.dart';
import '../util/hangul.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import '../widgets/net_image.dart';
import '../widgets/offline_view.dart';
import '../widgets/score_widgets.dart';
import 'item_detail_screen.dart';
import 'notifications_screen.dart';
import 'region_screen.dart';

/// 홈: 위쪽 '내 동네 · 검색 · 알림', 칩 줄(AI 추천 · 최신 · 카테고리), 물건 목록
///   AI 추천 = GET /api/feed (추천 알고리즘 점수순)   최신 = GET /api/items?category=&q=
class FeedScreen extends StatefulWidget {
  const FeedScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<FeedScreen> createState() => _FeedScreenState();
}

enum _Mode { recommended, latest }

class _FeedScreenState extends State<FeedScreen> {
  _Mode _mode = _Mode.recommended;
  String? _category;
  String? _query;
  bool _searching = false;
  final _search = TextEditingController();
  late Future<Object> _future;

  /// 검색창 추천 목록(유튜브처럼 입력하는 동안 아래에 뜬다). 글자를 멈추고 0.25초 뒤 서버 검색(이름 · 동네 · 초성)
  Timer? _debounce;
  String _typed = '';
  List<Item>? _suggestions;
  bool _suggesting = false;

  /// 최근 검색어(기기에 저장, 최근 것부터 10개). 검색창을 열면 시계 아이콘과 함께 보인다.
  static const _historyKey = 'sbada.search.history';
  List<String> _history = [];

  /// 홈 왼쪽 위 범위(내 동네 · 시 · 도 · 전국). 기기에 저장, 기본 = 전국(예전과 같은 화면)
  static const _areaKey = 'sbada.feed.area';
  AreaLevel _level = AreaLevel.nationwide;
  Future<int>? _countFuture;

  String get _region => widget.services.session.user?.region ?? '';
  AreaOption get _scope => areaOptionFor(_region, _level);

  Future<void> _loadArea() async {
    try {
      final raw = await widget.services.session.store.read(_areaKey);
      final level = AreaLevel.values.where((l) => l.name == raw).firstOrNull;
      if (level != null && level != _level && mounted) {
        setState(() {
          _level = level;
          _load();
        });
      }
    } catch (_) {
      // 저장소를 못 읽으면 전국
    }
  }

  /// 시트에서 '추가한 동네로 바꾸기' / '동네 추가하기'를 눌렀을 때 돌려주는 값
  static const _swapRegion = 'swap-region';
  static const _addRegion = 'add-region';

  /// 범위 고르기: 아래에서 올라오는 시트. 단계마다 '상품 N개'(GET /api/items/count)
  /// 맨 위 '내 동네'(최대 2개): 계정정보에서 추가한 동네를 누르면 지금 동네와 바꾼다(POST /api/me/region/swap).
  Future<void> _pickArea() async {
    final options = areaOptions(_region);
    final items = widget.services.items;
    final second = widget.services.session.user?.secondRegion;
    final picked = await showModalBottomSheet<Object>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      backgroundColor: Sb.card,
      builder: (context) => SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
          child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('내 동네', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Sb.navy)),
            const SizedBox(height: 4),
            Text(
                second == null
                    ? '계정정보 > 동네 추가에서 동네를 하나 더 넣으면 여기서 바꿀 수 있어요(최대 2개).'
                    : '추가한 동네를 누르면 그 동네 기준으로 홈과 동네지도가 바뀌어요.',
                style: sbCaption),
            const SizedBox(height: 8),
            ListTile(
              key: const ValueKey('region-current'),
              contentPadding: const EdgeInsets.symmetric(horizontal: 4),
              leading: Icon(Icons.place_rounded, color: Sb.mintText),
              title: Text(_region.isEmpty ? '우리 동네' : _region,
                  style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Sb.navy)),
              subtitle: Text('지금 보는 동네', style: sbCaption),
              trailing: Icon(Icons.check_rounded, color: Sb.mintText),
            ),
            if (second != null)
              ListTile(
                key: const ValueKey('region-second'),
                contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                leading: Icon(Icons.place_outlined, color: Sb.text3),
                title: Text(second, style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Sb.navy)),
                subtitle: Text('추가한 동네 · 눌러서 이 동네로 바꾸기', style: sbCaption),
                trailing: Icon(Icons.swap_horiz_rounded, color: Sb.mintText),
                onTap: () => Navigator.of(context).pop(_swapRegion),
              )
            else
              ListTile(
                key: const ValueKey('region-add'),
                contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                leading: Icon(Icons.add_location_alt_outlined, color: Sb.mintText),
                title: Text('동네 추가하기', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Sb.mintText)),
                onTap: () => Navigator.of(context).pop(_addRegion),
              ),
            Divider(height: 24, color: Sb.line),
            Text('어디까지 볼까요?', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Sb.navy)),
            const SizedBox(height: 4),
            Text('고른 범위 안의 물건만 홈 목록과 AI 추천에 보여 드려요.', style: sbCaption),
            const SizedBox(height: 12),
            for (final o in options)
              ListTile(
                key: ValueKey('area-${o.level.name}'),
                contentPadding: const EdgeInsets.symmetric(horizontal: 4),
                leading: Icon(
                    o.level == _scope.level ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded,
                    color: o.level == _scope.level ? Sb.mintText : Sb.text3),
                title: Text(o.label, style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700, color: Sb.navy)),
                subtitle: Text(o.kind, style: sbCaption),
                trailing: FutureBuilder<int>(
                  future: items.count(area: o.area),
                  builder: (context, snap) => Text(
                    snap.hasData ? '상품 ${snap.data}개' : (snap.hasError ? '' : '…'),
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Sb.mintText),
                  ),
                ),
                onTap: () => Navigator.of(context).pop(o.level),
              ),
          ]),
        ),
      ),
    );
    if (picked == null || !mounted) return;
    if (picked == _swapRegion) return _swapToSecond();
    if (picked == _addRegion) {
      await Navigator.of(context)
          .push(MaterialPageRoute<void>(builder: (_) => RegionScreen(services: widget.services, second: true)));
      return;
    }
    if (picked is! AreaLevel) return;
    setState(() {
      _level = picked;
      _load();
    });
    widget.services.session.store.write(_areaKey, picked.name).catchError((Object _) {});
  }

  /// 지금 동네 ↔ 추가한 동네(서버가 바꾸고, 홈 · 동네지도 · 계정정보가 새 동네로 다시 그린다)
  Future<void> _swapToSecond() async {
    try {
      final me = await widget.services.auth.swapRegion();
      widget.services.itemsChanged.value++;
      if (mounted) showSnack(context, '\'${me.user.region}\'(으)로 바꿨어요.');
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    }
  }

  @override
  void initState() {
    super.initState();
    _load();
    widget.services.itemsChanged.addListener(_onChanged);
    _loadHistory();
    _loadArea();
  }

  Future<void> _loadHistory() async {
    try {
      final raw = await widget.services.session.store.read(_historyKey);
      if (raw != null && mounted) setState(() => _history = List<String>.from(jsonDecode(raw) as List));
    } catch (_) {
      // 저장소를 못 읽으면 기록 없이 시작
    }
  }

  void _saveHistory(List<String> list) {
    setState(() => _history = list);
    widget.services.session.store.write(_historyKey, jsonEncode(list)).catchError((Object _) {});
  }

  void _remember(String q) {
    if (q.isEmpty) return;
    _saveHistory([q, ..._history.where((h) => h != q)].take(10).toList());
  }

  @override
  void dispose() {
    widget.services.itemsChanged.removeListener(_onChanged);
    _debounce?.cancel();
    _search.dispose();
    super.dispose();
  }

  void _onTyping(String v) {
    setState(() {
      _typed = v;
      _suggesting = true; // 비어 있으면 최근 검색어, 글자가 있으면 검색어 후보
      if (v.trim().isEmpty) _suggestions = null;
    });
    _debounce?.cancel();
    if (v.trim().isEmpty) return;
    _debounce = Timer(const Duration(milliseconds: 250), () async {
      final q = v.trim();
      try {
        final list = await widget.services.items.latest(q: q, limit: 8);
        if (mounted && _typed.trim() == q) setState(() => _suggestions = list);
      } catch (_) {
        if (mounted && _typed.trim() == q) setState(() => _suggestions = const []);
      }
    });
  }

  /// 검색 실행(엔터 · 추천의 '동네 물건 모두 보기' · ↖ 버튼 뒤 엔터)
  void _submit(String v) {
    _debounce?.cancel();
    FocusScope.of(context).unfocus();
    _remember(v.trim());
    setState(() {
      _search.text = v;
      _typed = v;
      _suggesting = false;
      _query = v.trim().isEmpty ? null : v.trim();
      _mode = _Mode.latest;
      _load();
    });
  }

  void _onChanged() {
    if (mounted) setState(_load);
  }

  void _load() {
    final items = widget.services.items;
    final area = _scope.area;
    // 인터넷이 없거나 약하면 15초 동안 '불러오는 중'을 유지한 뒤 '화면을 불러오지 못했어요'(OfflineView)
    _future = holdNetworkError(_mode == _Mode.recommended
        ? items.feed(area: area).then<Object>((v) => v)
        : items.latest(category: _category, q: _query, area: area).then<Object>((v) => v));
    _countFuture = items.count(area: area);
  }

  Future<void> _refresh() async {
    setState(_load);
    try {
      await _future;
    } catch (_) {
      // 화면에서 오류 상태로 보여 준다
    }
  }

  void _select(_Mode mode, {String? category}) {
    setState(() {
      _mode = mode;
      _category = category;
      _load();
    });
  }

  Future<void> _open(Item item) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ItemDetailScreen(services: widget.services, itemId: item.id),
      ),
    );
  }

  Future<void> _openNotifications() async {
    await Navigator.of(
      context,
    ).push(MaterialPageRoute<void>(builder: (_) => NotificationsScreen(services: widget.services)));
    widget.services.refreshBadges();
  }

  @override
  Widget build(BuildContext context) {
    final region = widget.services.session.user?.region ?? '';
    final dong = region.isEmpty ? '우리 동네' : region.split(' ').last;
    return Scaffold(
      appBar: AppBar(
        toolbarHeight: 60,
        titleSpacing: 16,
        title: _searching
            ? TextField(
                controller: _search,
                autofocus: true,
                textInputAction: TextInputAction.search,
                style: TextStyle(color: Sb.navy, fontSize: 16),
                decoration: InputDecoration(
                  hintText: '물건 이름으로 검색 (초성 검색 예시: ㅁㄴㅌ)',
                  hintStyle: TextStyle(fontSize: 14, color: Sb.text3),
                  isDense: true,
                  filled: true,
                  fillColor: Sb.bg,
                  prefixIcon: Icon(Icons.search, color: Sb.text3),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(999), borderSide: BorderSide.none),
                ),
                onChanged: _onTyping,
                onSubmitted: _submit,
              )
            : InkWell(
                borderRadius: BorderRadius.circular(8),
                onTap: _pickArea, // 범위 고르기(내 동네 · 시 · 도 · 전국)
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.place_rounded, size: 20, color: Sb.mintText),
                      const SizedBox(width: 4),
                      Text(dong, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
                      // 지금 범위(내 동네면 따로 안 씀): '망원동 · 전국 ▾'
                      if (_scope.level != AreaLevel.dong) ...[
                        const SizedBox(width: 6),
                        Text('· ${_scope.label}',
                            style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: Sb.text2)),
                      ],
                      Icon(Icons.keyboard_arrow_down_rounded, color: Sb.text2),
                    ],
                  ),
                ),
              ),
        actions: [
          IconButton(
            tooltip: _searching ? '검색 닫기' : '검색',
            icon: Icon(_searching ? Icons.close_rounded : Icons.search_rounded, size: 26),
            onPressed: () => setState(() {
              _searching = !_searching;
              _debounce?.cancel();
              _suggesting = _searching; // 검색창을 열면 바로 최근 검색어를 보여 준다
              _suggestions = null;
              _typed = '';
              if (!_searching) {
                _search.clear();
                if (_query != null) {
                  _query = null;
                  _load();
                }
              }
            }),
          ),
          IconButton(
            tooltip: '알림',
            onPressed: _openNotifications,
            icon: ValueListenableBuilder<int>(
              valueListenable: widget.services.unreadNotifications,
              builder: (context, n, child) =>
                  Badge(isLabelVisible: n > 0, smallSize: 8, backgroundColor: Sb.blue, child: child),
              child: const Icon(Icons.notifications_none_rounded, size: 26),
            ),
          ),
          const SizedBox(width: 6),
        ],
      ),
      body: _searching && _suggesting
          ? _suggestionList()
          : RefreshIndicator(
              onRefresh: _refresh,
              child: CustomScrollView(
                slivers: [
                  SliverToBoxAdapter(child: _chipBar()),
                  // 지금 범위 안의 물건 수: '📍 마포구 · 상품 20개' (범위를 바꾸면 숫자가 바뀐다)
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(16, 2, 16, 0),
                      child: FutureBuilder<int>(
                        future: _countFuture,
                        builder: (context, snap) => Row(children: [
                          Icon(Icons.place_outlined, size: 15, color: Sb.mintText),
                          const SizedBox(width: 4),
                          Text(
                            snap.hasData ? '${_scope.label} · 상품 ${snap.data}개' : _scope.label,
                            key: const ValueKey('area-count'),
                            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Sb.mintText),
                          ),
                        ]),
                      ),
                    ),
                  ),
                  FutureBuilder<Object>(
                    future: _future,
                    builder: (context, snap) {
                      if (snap.connectionState != ConnectionState.done) {
                        return const SliverFillRemaining(child: LoadingView());
                      }
                      if (snap.hasError) {
                        final e = snap.error!;
                        return SliverFillRemaining(
                          hasScrollBody: false,
                          child: e is ApiException && e.isNetwork
                              ? OfflineView(onRetry: () => setState(_load))
                              : ErrorView(error: e, onRetry: () => setState(_load)),
                        );
                      }
                      final data = snap.data!;
                      if (data is FeedResult) return _recommendations(data);
                      return _latest(data as List<Item>);
                    },
                  ),
                  const SliverToBoxAdapter(child: SizedBox(height: Sb.tabBarSpace + 56)),
                ],
              ),
            ),
    );
  }

  /// 유튜브식 검색창 아래 목록
  ///  - 글자가 없으면: 최근 검색어(🕘, 오른쪽 X 로 지우기)
  ///  - 글자가 있으면: 맞는 최근 검색어(🕘) → 검색어 후보(🔍, 상품 이름 · 동네 이름 글자만). '모' → '모니터'처럼
  ///    입력한 글자로 '시작하는' 후보가 먼저. 줄을 누르면 그 말로 검색, ↖ 는 검색창에 채우기
  Widget _suggestionList() {
    final q = _typed.trim();
    void fill(String text) {
      _search.text = text;
      _search.selection = TextSelection.collapsed(offset: text.length);
      _onTyping(text);
    }

    if (q.isEmpty) {
      return ListView(
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        padding: const EdgeInsets.only(top: 4, bottom: Sb.tabBarSpace),
        children: [
          if (_history.isEmpty)
            Padding(
              padding: const EdgeInsets.all(24),
              child: Text('최근 검색어가 없어요.\n물건 이름 · 동네(예: 망원동) · 초성(예: ㅁㄴㅌ)으로 찾아보세요.',
                  style: sbBody, textAlign: TextAlign.center),
            ),
          for (final h in _history)
            _suggestRow(
              leading: Icons.history_rounded,
              title: TextSpan(text: h, style: TextStyle(fontSize: 15, color: Sb.navy)),
              onTap: () => _submit(h),
              onFill: () => fill(h),
              onRemove: () => _saveHistory(_history.where((x) => x != h).toList()),
            ),
        ],
      );
    }

    final list = _suggestions;
    final pastHits = _history.where((h) => h != q && hangulMatch(h, q) != null).take(3).toList();
    // 후보 글자: 상품 이름 · 동네 이름을 나눠서(중복 없이), 입력으로 시작하는 것 먼저
    final titles = <String>{}, dongs = <String>{};
    for (final it in list ?? const <Item>[]) {
      if (hangulMatch(it.title, q) != null) titles.add(it.title);
      final dong = it.owner.region.split(' ').last;
      if (hangulMatch(dong, q) != null) dongs.add(dong);
    }
    List<String> ordered(Set<String> s) => s.where((w) => !pastHits.contains(w)).toList()
      ..sort((a, b) {
        final sa = hangulMatch(a, q)!.$1 == 0 ? 0 : 1, sb = hangulMatch(b, q)!.$1 == 0 ? 0 : 1;
        return sa != sb ? sa - sb : a.length - b.length;
      });
    final productWords = ordered(titles).take(8).toList();
    final regionWords = ordered(dongs).take(4).toList();
    Widget section(String label) => Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 16, 2),
          child: Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: Sb.text3)),
        );
    return ListView(
      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
      padding: const EdgeInsets.only(top: 4, bottom: Sb.tabBarSpace),
      children: [
        for (final h in pastHits)
          _suggestRow(leading: Icons.history_rounded, title: _highlight(h, q), onTap: () => _submit(h), onFill: () => fill(h)),
        // 위: 상품 이름 후보 / 아래: 지역(동네) 후보 — 묶음마다 왼쪽 위에 작은 제목
        if (productWords.isNotEmpty) section('상품'),
        for (final w in productWords)
          _suggestRow(leading: Icons.search_rounded, title: _highlight(w, q), onTap: () => _submit(w), onFill: () => fill(w)),
        if (regionWords.isNotEmpty) section('지역'),
        for (final d in regionWords)
          _suggestRow(leading: Icons.place_outlined, title: _highlight(d, q), onTap: () => _submit(d), onFill: () => fill(d)),
        if (list == null)
          const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
        else if (productWords.isEmpty && regionWords.isEmpty && pastHits.isEmpty)
          Padding(
            padding: const EdgeInsets.all(24),
            child: Text("'$q'와(과) 맞는 물건이 없어요.\n이름 · 동네(예: 망원동) · 초성(예: ㅁㄴㅌ)으로 찾아보세요.",
                style: sbBody, textAlign: TextAlign.center),
          ),
      ],
    );
  }

  Widget _suggestRow({
    required IconData leading,
    required InlineSpan title,
    String? subtitle,
    String? image,
    required VoidCallback onTap,
    VoidCallback? onFill,
    VoidCallback? onRemove,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 4, 8),
        child: Row(
          children: [
            Icon(leading, size: 22, color: Sb.text2),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text.rich(title, maxLines: 1, overflow: TextOverflow.ellipsis),
                  if (subtitle != null) Text(subtitle, style: TextStyle(fontSize: 12, color: Sb.text3)),
                ],
              ),
            ),
            if (image != null) ...[const SizedBox(width: 8), NetImage(image, size: 40, radius: 8)],
            if (onRemove != null)
              IconButton(
                tooltip: '검색 기록 지우기',
                onPressed: onRemove,
                icon: Icon(Icons.close_rounded, size: 18, color: Sb.text3),
              ),
            if (onFill != null)
              IconButton(
                tooltip: '검색창에 넣기',
                onPressed: onFill,
                icon: Transform.rotate(
                  angle: -0.785,
                  child: Icon(Icons.arrow_upward_rounded, size: 20, color: Sb.text3),
                ),
              )
            else
              const SizedBox(width: 12),
          ],
        ),
      ),
    );
  }

  /// 검색어와 맞는 부분만 굵게(초성 검색도 맞은 글자를 굵게)
  InlineSpan _highlight(String text, String q) {
    final base = TextStyle(fontSize: 15, color: Sb.text2);
    final m = hangulMatch(text, q);
    if (m == null) return TextSpan(text: text, style: base);
    final (s, e) = m;
    return TextSpan(
      style: base,
      children: [
        TextSpan(text: text.substring(0, s)),
        TextSpan(
          text: text.substring(s, e),
          style: TextStyle(fontWeight: FontWeight.w800, color: Sb.navy),
        ),
        TextSpan(text: text.substring(e)),
      ],
    );
  }

  /// 칩 줄: [AI 추천] [최신] | 카테고리들 — 선택된 칩은 밝은 색으로 뒤집힌다
  Widget _chipBar() {
    return SizedBox(
      height: 52,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.fromLTRB(16, 6, 16, 8),
        children: [
          _chip('AI 추천', _mode == _Mode.recommended, () => _select(_Mode.recommended)),
          _chip('최신', _mode == _Mode.latest && _category == null, () => _select(_Mode.latest)),
          for (final c in CategoryInfo.all)
            _chip(c.label, _mode == _Mode.latest && _category == c.code, () => _select(_Mode.latest, category: c.code)),
        ],
      ),
    );
  }

  Widget _chip(String label, bool selected, VoidCallback onTap) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Material(
        color: selected ? Sb.navy : Sb.chip,
        shape: const StadiumBorder(),
        child: InkWell(
          customBorder: const StadiumBorder(),
          onTap: onTap,
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 36),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14),
              child: Center(
                child: Text(
                  label,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                    color: selected ? Sb.base : Sb.navy,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _recommendations(FeedResult feed) {
    if (feed.items.isEmpty) {
      return const SliverFillRemaining(
        child: EmptyView(emoji: '🔍', title: '아직 추천할 물건이 없어요', message: '다른 사람이 물건을 올리면 여기에 보여요.'),
      );
    }
    return SliverList.separated(
      itemCount: feed.items.length + 1,
      separatorBuilder: (context, i) =>
          i == 0 ? const SizedBox.shrink() : const Divider(height: 1, indent: 16, endIndent: 16),
      itemBuilder: (context, i) {
        if (i == 0) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
            child: Text('선호도 40% + 가격 적합도 30% + 신선도 15% + 인기도 15%', style: sbCaption),
          );
        }
        final rec = feed.items[i - 1];
        return ItemTile(
          item: rec.item,
          onTap: () => _open(rec.item),
          trailing: GestureDetector(
            onTap: () => showScoreSheet(context, rec, title: '왜 추천됐나요?'),
            child: ScoreText(rec.score),
          ),
          footer: ReasonChips(
            rec.reasons,
            // 첫 이유(민트 칩)만 보여 준다. 아래 회색 칩('내 ○○와 비슷한 가격')은 지움(2026-10-22 사용자 요청) —
            // 전체 이유는 오른쪽 점수를 누르면 나오는 '왜 추천됐나요?' 시트에 그대로 있다
            max: 1,
            extra: [
              if (rec.requestStatus == 'REQUESTED')
                Pill('교환 요청 보냄', color: Sb.greenSoft, textColor: Sb.green, bold: true),
            ],
          ),
        );
      },
    );
  }

  Widget _latest(List<Item> items) {
    if (items.isEmpty) {
      return SliverFillRemaining(
        child: EmptyView(emoji: '📭', title: _query != null ? "'$_query' 검색 결과가 없어요" : '이 카테고리에는 아직 물건이 없어요'),
      );
    }
    return SliverList.separated(
      itemCount: items.length,
      separatorBuilder: (context, i) => const Divider(height: 1, indent: 16, endIndent: 16),
      itemBuilder: (context, i) => ItemTile(item: items[i], onTap: () => _open(items[i])),
    );
  }
}
