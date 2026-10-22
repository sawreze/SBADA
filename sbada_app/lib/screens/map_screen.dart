import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../map/geo.dart';
import '../map/kakao_map_view.dart';
import '../map/map_config.dart';
import '../models/item.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/glass.dart';
import '../widgets/match_pin.dart';
import '../widgets/net_image.dart';
import 'item_detail_screen.dart';

/// 동네지도: 우리 동네 주변의 교환 가능한 물건을 '매치도 핀'으로 보여 준다.
///  - 핀 = 위쪽 원에 올린 물건 사진 + 아래에 나와의 매치도 %(홈 AI 추천과 같은 점수, GET /api/feed).
///    매치도가 높을수록 핀 테두리를 따라 도는 초록 빛이 더 밝게 번진다(widgets/match_pin.dart).
///  - 지도: OpenStreetMap(기본, 키 없음) 또는 카카오맵(map/kakao_map_view.dart),
///    없거나 못 띄우면 동네 배치를 직접 그린 '약도'로 대신한다(시연이 인터넷·키에 묶이지 않게).
///  - 물건의 위치 = 등록자의 동네 중심 + 물건 번호로 정한 작은 흩어짐(같은 동네 핀이 겹치지 않게).
///  - 아래 목록 시트는 내 동네에서 가까운 순서로 정렬한다.
class MapScreen extends StatefulWidget {
  const MapScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<MapScreen> createState() => _MapScreenState();
}

/// 약도 좌표계(가로 1200 × 세로 1600). 키가 없을 때만 쓰는 그림 좌표(거리는 map/geo.dart 의 실제 좌표로 잰다).
const _mapSize = Size(1200, 1600);

/// 동네 중심 위치(마포구·서대문구 일대의 상대 배치)
const Map<String, Offset> _dongs = {
  '상암동': Offset(200, 260),
  '성산동': Offset(360, 560),
  '연희동': Offset(900, 300),
  '연남동': Offset(800, 660),
  '망원동': Offset(330, 960),
  '서교동': Offset(800, 980),
  '합정동': Offset(560, 1170),
  '상수동': Offset(880, 1260),
};

Offset _dongCenter(String region) {
  final dong = region.trim().isEmpty ? '' : region.trim().split(' ').last;
  final known = _dongs[dong];
  if (known != null) return known;
  // 모르는 동네는 이름으로 정한 고정 위치(앱을 다시 켜도 같은 자리)
  final h = dong.codeUnits.fold<int>(7, (a, c) => (a * 31 + c) & 0x7fffffff);
  return Offset(200 + (h % 800).toDouble(), 200 + ((h ~/ 800) % 1100).toDouble());
}

Offset _itemPoint(Item item) {
  final c = _dongCenter(item.owner.region);
  final angle = item.id * 2.39996; // 황금각: 번호가 이어져도 고르게 퍼진다
  final r = 55.0 + (item.id * 37 % 70);
  return c + Offset(math.cos(angle) * r, math.sin(angle) * r);
}

/// 350m · 1.2km 처럼 목록에 쓰는 거리(디자인 시안)
String _distanceLabel(double km) {
  if (km < 1) return '${math.max(10, (km * 100).round() * 10)}m';
  return '${km.toStringAsFixed(1)}km';
}

class _MapScreenState extends State<MapScreen> {
  final _transform = TransformationController();
  final _kakao = KakaoMapController();
  late Future<List<Item>> _future;

  /// 물건 번호 → 나와의 매치도(0~100). 추천 점수를 못 받으면 빈 지도(핀에 '–')
  Map<int, int> _scores = const {};

  /// 아래 목록 정렬: false = 가까운 순, true = 매치 높은 순(오른쪽 위 전환 버튼)
  bool _byMatch = false;
  MapConfig? _mapConfig;
  bool _kakaoFailed = false;
  Item? _selected;
  bool _centered = false;
  Size _viewport = Size.zero;

  @override
  void initState() {
    super.initState();
    _load();
    widget.services.itemsChanged.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.services.itemsChanged.removeListener(_onChanged);
    _transform.dispose();
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(_load);
  }

  void _load() {
    final me = widget.services.session.user?.id;
    // 교환 가능한 '남의' 물건만 지도에 올린다(내 물건은 계정정보 > 내 물건에서 본다)
    _future = widget.services.items
        .latest()
        .then((list) => list.where((i) => i.isAvailable && i.owner.id != me).toList());
    _loadScores();
    MapConfig.load(widget.services.api).then((c) {
      if (mounted) setState(() => _mapConfig = c);
    });
  }

  /// 매치도 = 홈 'AI 추천'과 같은 추천 점수(선호도 · 가격 적합도 · 신선도 · 인기도).
  /// 지도에 보이는 물건 전부의 점수를 받는다(예전엔 피드 상위 60개만 있어서 용산 등 다른 물건 핀이 '–' 였음).
  Future<void> _loadScores() async {
    try {
      final shown = await _future;
      final scores = await widget.services.items.scores([for (final i in shown) i.id]);
      if (!mounted) return;
      setState(() => _scores = scores);
    } catch (_) {
      // 로그인 전이거나 서버 오류면 점수 없이 핀만 보여 준다
    }
  }

  bool get _useKakao => (_mapConfig?.hasMap ?? false) && !_kakaoFailed;

  LatLng get _meGeo => dongLatLng(widget.services.session.user?.region ?? '');

  Offset get _me => _dongCenter(widget.services.session.user?.region ?? '');

  /// 지도 한 점을 화면 가운데(아래 시트를 뺀 윗부분의 가운데)로 옮긴다.
  void _centerOn(Offset p, {double scale = 1.0}) {
    if (_viewport == Size.zero) return;
    final target = Offset(_viewport.width / 2, _viewport.height * 0.36);
    _transform.value = Matrix4.identity()
      ..translateByDouble(target.dx - p.dx * scale, target.dy - p.dy * scale, 0, 1)
      ..scaleByDouble(scale, scale, 1, 1);
  }

  /// − / + 버튼. 실제 지도(WebView)는 지도에 맡기고, 약도는 화면 가운데를 기준으로 1.4배씩 키우고 줄인다.
  void _zoom(int d) {
    if (_useKakao) {
      _kakao.zoomBy(d);
      return;
    }
    if (_viewport == Size.zero) return;
    final current = _transform.value.getMaxScaleOnAxis();
    final next = (current * (d > 0 ? 1.4 : 1 / 1.4)).clamp(0.5, 2.5);
    final f = next / current;
    final c = Offset(_viewport.width / 2, _viewport.height * 0.36);
    _transform.value = Matrix4.identity()
      ..translateByDouble(c.dx, c.dy, 0, 1)
      ..scaleByDouble(f, f, 1, 1)
      ..translateByDouble(-c.dx, -c.dy, 0, 1)
      ..multiply(_transform.value);
  }

  /// 목록 시트 왼쪽 위에 붙는 유리 − / + 버튼(세로). 48dp 터치 영역
  Widget _zoomButtons() {
    Widget btn(IconData icon, String tip, int d) => Tooltip(
          message: tip,
          child: InkWell(
            onTap: () => _zoom(d),
            child: SizedBox(width: 48, height: 44, child: Icon(icon, color: Sb.navy, size: 22)),
          ),
        );
    return Glass(
      radius: 16,
      tint: Sb.glassButton,
      child: Material(
        type: MaterialType.transparency,
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          btn(Icons.add_rounded, '지도 확대', 1),
          Container(width: 28, height: 1, color: Sb.line),
          btn(Icons.remove_rounded, '지도 축소', -1),
        ]),
      ),
    );
  }

  /// 거리는 실제 좌표(위도·경도)로 잰다. 약도에서도 같은 값을 보여 준다.
  double _km(Item item) => itemLatLng(item).kmTo(_meGeo);

  /// 지도(카카오맵이든 약도든)를 물건 위치로 옮긴다
  void _focus(Item item) {
    if (_useKakao) {
      _kakao.panTo(itemLatLng(item), level: 4);
    } else {
      _centerOn(_itemPoint(item), scale: 1.2);
    }
  }

  void _focusMe() {
    if (_useKakao) {
      _kakao.panTo(_meGeo, level: 5);
    } else {
      _centerOn(_me, scale: 0.8);
    }
  }

  Future<void> _open(Item item) async {
    await Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => ItemDetailScreen(services: widget.services, itemId: item.id)));
  }

  @override
  Widget build(BuildContext context) {
    final region = widget.services.session.user?.region ?? '';
    final dong = region.isEmpty ? '우리 동네' : region.split(' ').last;
    final top = MediaQuery.of(context).padding.top;
    return Scaffold(
      backgroundColor: _MapColors.of(Sb.isDark).ground,
      body: FutureBuilder<List<Item>>(
        future: _future,
        builder: (context, snap) {
          final items = [...?snap.data]..sort((a, b) => _km(a).compareTo(_km(b)));
          return LayoutBuilder(builder: (context, c) {
            _viewport = c.biggest;
            if (!_centered && _viewport != Size.zero) {
              _centered = true;
              WidgetsBinding.instance.addPostFrameCallback((_) => _centerOn(_me, scale: 0.8));
            }
            return Stack(children: [
              // ── 지도(끌어서 이동, 두 손가락으로 확대) ──
              if (_useKakao)
                Positioned.fill(
                  child: KakaoMapView(
                    jsKey: _mapConfig!.jsKeyForView,
                    center: _meGeo,
                    level: 5,
                    me: _meGeo,
                    controller: _kakao,
                    selectedId: _selected?.id,
                    pins: _kakaoPins(items),
                    onPinTap: (id) {
                      final hit = items.where((i) => i.id == id);
                      if (hit.isNotEmpty) setState(() => _selected = _selected?.id == id ? null : hit.first);
                    },
                    onMapTap: () {
                      if (_selected != null) setState(() => _selected = null);
                    },
                    onError: (why) {
                      debugPrint('지도를 띄우지 못해 약도로 바꿉니다: $why');
                      if (mounted) setState(() => _kakaoFailed = true);
                    },
                  ),
                )
              else
              Positioned.fill(
                child: InteractiveViewer(
                  transformationController: _transform,
                  constrained: false,
                  minScale: 0.5,
                  maxScale: 2.5,
                  boundaryMargin: const EdgeInsets.all(400),
                  child: SizedBox.fromSize(
                    size: _mapSize,
                    child: Stack(clipBehavior: Clip.none, children: [
                      Positioned.fill(child: CustomPaint(painter: _MapPainter(Sb.isDark))),
                      _myLocation(_me),
                      // 매치도가 높은 핀(더 빛나는 핀)과 고른 핀이 위에 오도록 그리는 순서를 정한다
                      for (final it in _drawOrder(items)) _pin(it),
                    ]),
                  ),
                ),
              ),
              // ── 위: 유리 검색 알약 + 내 위치 버튼 ──
              Positioned(
                left: 16,
                right: 16,
                top: top + 10,
                child: Row(children: [
                  Expanded(
                    child: Glass(
                      tint: Sb.glassButton,
                      child: SizedBox(
                        height: 48,
                        child: Row(children: [
                          const SizedBox(width: 16),
                          Icon(Icons.place_outlined, color: Sb.mintText, size: 20),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text('$dong 주변',
                                style: TextStyle(color: Sb.navy, fontSize: 16, fontWeight: FontWeight.w800),
                                overflow: TextOverflow.ellipsis),
                          ),
                          if (items.isNotEmpty)
                            Text('반경 ${_km(items.last).ceil().clamp(1, 999)}km',
                                style: TextStyle(color: Sb.text2, fontSize: 13, fontWeight: FontWeight.w600)),
                          const SizedBox(width: 16),
                        ]),
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  GlassIconButton(
                    icon: Icons.my_location_rounded,
                    tooltip: '내 동네로',
                    onPressed: () => setState(() {
                      _selected = null;
                      _focusMe();
                    }),
                  ),
                ]),
              ),
              if (snap.hasError)
                Positioned(
                  left: 16,
                  right: 16,
                  top: top + 70,
                  child: ErrorView(error: snap.error!, onRetry: () => setState(_load)),
                ),
              // ── 선택한 핀의 미리보기 카드 ──
              if (_selected != null)
                Positioned(
                  left: 16,
                  right: 16,
                  bottom: c.maxHeight * 0.30 + 12,
                  child: _preview(_selected!),
                ),
              // ── 목록 왼쪽 위: 지도 확대 · 축소(핀 미리보기 카드가 열리면 그 위로) ──
              Positioned(
                left: 16,
                bottom: c.maxHeight * 0.30 + 12 + (_selected != null ? 112 : 0),
                child: _zoomButtons(),
              ),
              // ── 아래: 끌어올리는 목록 시트 ──
              DraggableScrollableSheet(
                initialChildSize: 0.30,
                minChildSize: 0.16,
                maxChildSize: 0.88,
                snap: true,
                snapSizes: const [0.30, 0.88],
                builder: (context, scroll) => _sheet(scroll, items, dong, snap.hasData),
              ),
            ]);
          });
        },
      ),
    );
  }

  /// 내 위치: 민트 점 + 둘레의 옅은 민트 원(디자인 시안의 '반경' 표시)
  Widget _myLocation(Offset p) {
    return Positioned(
      left: p.dx - 60,
      top: p.dy - 60,
      child: IgnorePointer(
        child: Container(
          width: 120,
          height: 120,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: Sb.blue.withValues(alpha: 0.08),
            border: Border.all(color: Sb.blue.withValues(alpha: 0.3)),
          ),
          alignment: Alignment.center,
          child: Container(
            width: 16,
            height: 16,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: Sb.blue,
              border: Border.all(color: Sb.base, width: 3),
              boxShadow: [BoxShadow(color: Sb.blue.withValues(alpha: 0.25), spreadRadius: 6)],
            ),
          ),
        ),
      ),
    );
  }

  /// 매치도 핀(물방울 모양): 위쪽 원에 물건 사진, 아래에 매치도 %. 꼬리 끝이 물건 위치를 가리킨다.
  Widget _pin(Item item) {
    final p = _itemPoint(item);
    final selected = _selected?.id == item.id;
    final pin = MatchPin(imagePath: item.firstImage, score: _scores[item.id], selected: selected);
    final size = pin.outerSize;
    return Positioned(
      // 꼬리 끝(가로 가운데, 아래 빛 여백 바로 위)이 물건 위치 p 에 오게 놓는다
      left: p.dx - size.width / 2,
      top: p.dy - size.height + MatchPin.glowPad,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _selected = selected ? null : item),
        child: pin,
      ),
    );
  }

  List<Item> _drawOrder(List<Item> items) => [...items]..sort((a, b) {
      if (a.id == _selected?.id) return 1;
      if (b.id == _selected?.id) return -1;
      return (_scores[a.id] ?? -1).compareTo(_scores[b.id] ?? -1);
    });

  /// 카카오맵에 줄 핀 목록. 물건·점수가 그대로면 같은 목록을 돌려줘 지도가 핀을 다시 그리지 않게 한다.
  List<MapPin>? _pinsCache;
  List<Item>? _pinsItems;
  Map<int, int>? _pinsScores;

  List<MapPin> _kakaoPins(List<Item> items) {
    final sameItems = _pinsItems != null &&
        _pinsItems!.length == items.length &&
        Iterable<int>.generate(items.length).every((i) => _pinsItems![i].id == items[i].id);
    if (_pinsCache != null && sameItems && identical(_pinsScores, _scores)) return _pinsCache!;
    _pinsItems = items;
    _pinsScores = _scores;
    return _pinsCache = [
      for (final it in items)
        MapPin(id: it.id, at: itemLatLng(it), imagePath: it.firstImage, score: _scores[it.id]),
    ];
  }

  Widget _preview(Item item) {
    return Glass(
      radius: 22,
      tint: Sb.isDark ? const Color(0xCC1E2126) : const Color(0xE6FFFFFF),
      child: Material(
        type: MaterialType.transparency,
        child: InkWell(
          onTap: () => _open(item),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(children: [
              NetImage(item.firstImage, size: 64, radius: 12),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(item.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: Sb.navy)),
                  const SizedBox(height: 2),
                  Text('${item.owner.region.split(' ').last} · ${_distanceLabel(_km(item))}', style: sbCaption),
                  const SizedBox(height: 4),
                  Row(children: [
                    PriceText(item.price),
                    if (_scores[item.id] != null) ...[
                      const SizedBox(width: 8),
                      Text('매치 ${_scores[item.id]}%',
                          style: TextStyle(fontSize: 13, fontWeight: FontWeight.w800, color: Sb.mintText)),
                    ],
                  ]),
                ]),
              ),
              Icon(Icons.chevron_right_rounded, color: Sb.text2),
            ]),
          ),
        ),
      ),
    );
  }

  Widget _sheet(ScrollController scroll, List<Item> nearFirst, String dong, bool loaded) {
    // 매치 높은 순: 점수 높은 것부터(점수를 모르는 물건은 뒤로, 같으면 가까운 순)
    final items = _byMatch
        ? ([...nearFirst]..sort((a, b) {
            final byScore = (_scores[b.id] ?? -1).compareTo(_scores[a.id] ?? -1);
            return byScore != 0 ? byScore : _km(a).compareTo(_km(b));
          }))
        : nearFirst;
    return Container(
      decoration: BoxDecoration(
        color: Sb.card,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        border: Border(top: BorderSide(color: Sb.glassStroke)),
        boxShadow: [BoxShadow(color: Sb.glassShadow, blurRadius: 30, offset: const Offset(0, -10))],
      ),
      child: CustomScrollView(controller: scroll, slivers: [
        SliverToBoxAdapter(
          child: Column(children: [
            const SizedBox(height: 8),
            Container(
                width: 36, height: 4, decoration: BoxDecoration(color: Sb.text3, borderRadius: BorderRadius.circular(2))),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: Row(children: [
                Expanded(
                  child: Text(loaded ? '가까운 물건 ${items.length}개' : '$dong 근처 물건 찾는 중…',
                      style: sbH2, maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
                if (loaded) _SortToggle(byMatch: _byMatch, onChanged: (v) => setState(() => _byMatch = v)),
              ]),
            ),
          ]),
        ),
        if (!loaded)
          const SliverToBoxAdapter(child: Padding(padding: EdgeInsets.all(32), child: LoadingView()))
        else if (items.isEmpty)
          const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.only(top: 24),
              child: EmptyView(emoji: '🗺️', title: '근처에 교환할 물건이 아직 없어요'),
            ),
          )
        else
          SliverList.separated(
            itemCount: items.length,
            separatorBuilder: (context, i) => const Divider(height: 1, indent: 16, endIndent: 16),
            itemBuilder: (context, i) => _nearRow(items[i]),
          ),
        const SliverToBoxAdapter(child: SizedBox(height: Sb.tabBarSpace)),
      ]),
    );
  }

  /// 디자인 시안(동네지도) 목록 줄: [사진 56] 이름 / 350m · 동네        가격(고른 물건은 민트)
  Widget _nearRow(Item it) {
    final selected = _selected?.id == it.id;
    return InkWell(
      onTap: () {
        setState(() => _selected = it);
        _focus(it);
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(children: [
          NetImage(it.firstImage, size: 56, radius: 12),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(it.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: Sb.navy)),
              const SizedBox(height: 2),
              Text('${_distanceLabel(_km(it))} · ${it.owner.region.split(' ').last}', style: sbCaption),
            ]),
          ),
          const SizedBox(width: 8),
          Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            Text(formatWon(it.price),
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: selected ? Sb.mintText : Sb.navy)),
            if (_scores[it.id] != null)
              Text('매치 ${_scores[it.id]}%',
                  style: TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: Sb.mintText)),
          ]),
        ]),
      ),
    );
  }
}

class _MapColors {
  const _MapColors(this.ground, this.blockA, this.blockB, this.park, this.road, this.minor, this.river, this.riverLabel,
      this.label);

  final Color ground, blockA, blockB, park, road, minor, river, riverLabel, label;

  static const dark = _MapColors(Color(0xFF15171B), Color(0xFF1D2026), Color(0xFF1F2329), Color(0xFF16291F),
      Color(0xFF2C3038), Color(0xFF24272E), Color(0xFF13273A), Color(0xFF3E6A8E), Color(0xFF6E7682));
  static const light = _MapColors(Color(0xFFEEF1F4), Color(0xFFE2E6EA), Color(0xFFE6E9ED), Color(0xFFD5EEDD),
      Color(0xFFFFFFFF), Color(0xFFF8F9FA), Color(0xFFCFE3F5), Color(0xFF6B9AC4), Color(0xFF8A929C));

  static _MapColors of(bool dark) => dark ? _MapColors.dark : _MapColors.light;
}

/// 약도: 블록 · 큰길 · 공원 · 한강 · 동네 이름 (모드가 바뀌면 다시 그린다)
class _MapPainter extends CustomPainter {
  const _MapPainter(this.dark);

  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final c = _MapColors.of(dark);
    canvas.drawRect(Offset.zero & size, Paint()..color = c.ground);

    // 블록(건물 덩어리): 고정 시드라 매번 같은 모양
    final rnd = math.Random(7);
    final block = Paint();
    for (double y = 20; y < size.height - 220; y += 92) {
      for (double x = 16; x < size.width; x += 108) {
        final w = 70 + rnd.nextDouble() * 22;
        final h = 58 + rnd.nextDouble() * 18;
        block.color = rnd.nextBool() ? c.blockA : c.blockB;
        canvas.drawRRect(RRect.fromRectAndRadius(Rect.fromLTWH(x, y, w, h), const Radius.circular(8)), block);
      }
    }

    // 공원(경의선숲길처럼 비스듬한 띠 + 공원 덩어리)
    final park = Paint()..color = c.park;
    canvas.drawPath(
        Path()
          ..moveTo(1000, 120)
          ..lineTo(1060, 150)
          ..lineTo(620, 1060)
          ..lineTo(560, 1030)
          ..close(),
        park);
    canvas.drawRRect(RRect.fromRectAndRadius(const Rect.fromLTWH(80, 120, 260, 180), const Radius.circular(40)), park);

    // 큰길
    final road = Paint()
      ..color = c.road
      ..strokeWidth = 16
      ..strokeCap = StrokeCap.round;
    final minor = Paint()
      ..color = c.minor
      ..strokeWidth = 7
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(const Offset(0, 760), const Offset(1200, 820), road);
    canvas.drawLine(const Offset(600, 0), const Offset(560, 1380), road);
    canvas.drawLine(const Offset(80, 1300), const Offset(1150, 420), road);
    for (final y in [300.0, 520.0, 1060.0]) {
      canvas.drawLine(Offset(0, y), Offset(1200, y + 30), minor);
    }
    for (final x in [220.0, 900.0, 1080.0]) {
      canvas.drawLine(Offset(x, 0), Offset(x - 40, 1380), minor);
    }

    // 한강(아래쪽을 가로지르는 물줄기)
    final river = Path()
      ..moveTo(0, 1380)
      ..cubicTo(300, 1330, 700, 1430, 1200, 1360)
      ..lineTo(1200, 1600)
      ..lineTo(0, 1600)
      ..close();
    canvas.drawPath(river, Paint()..color = c.river);
    _label(canvas, '한강', const Offset(600, 1500), 22, c.riverLabel);

    // 동네 이름
    _dongs.forEach((name, p) => _label(canvas, name, p + const Offset(0, 70), 16, c.label));
  }

  void _label(Canvas canvas, String text, Offset center, double size, Color color) {
    final tp = TextPainter(
      text: TextSpan(text: text, style: TextStyle(fontSize: size, fontWeight: FontWeight.w700, color: color)),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, center - Offset(tp.width / 2, tp.height / 2));
  }

  @override
  bool shouldRepaint(_MapPainter oldDelegate) => oldDelegate.dark != dark;
}

/// 동네지도 목록 정렬 전환(가까운 순 ↔ 매치 높은 순).
/// 계정정보의 다크 / 화이트 버튼(ThemeToggle)과 같은 모양: 유리 캡슐 안 두 칸, 고른 쪽이 민트로 채워진다.
class _SortToggle extends StatelessWidget {
  const _SortToggle({required this.byMatch, required this.onChanged});

  final bool byMatch;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    return Glass(
      tint: Sb.glassButton,
      blur: 12,
      child: Padding(
        padding: const EdgeInsets.all(3),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          _option('가까운 순', selected: !byMatch, onTap: () => onChanged(false)),
          _option('매치 높은 순', selected: byMatch, onTap: () => onChanged(true)),
        ]),
      ),
    );
  }

  Widget _option(String label, {required bool selected, required VoidCallback onTap}) {
    return Semantics(
      button: true,
      selected: selected,
      label: '$label으로 정렬',
      child: InkWell(
        customBorder: const StadiumBorder(),
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          height: 34,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          alignment: Alignment.center,
          decoration: ShapeDecoration(shape: const StadiumBorder(), color: selected ? Sb.blue : Colors.transparent),
          child: Text(label,
              style: TextStyle(
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w800 : FontWeight.w600,
                  color: selected ? Sb.onAccent : Sb.text2)),
        ),
      ),
    );
  }
}
