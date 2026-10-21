import 'package:flutter/material.dart';

import '../map/geo.dart';
import '../map/kakao_map_view.dart';
import '../map/map_config.dart';
import '../models/item.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/glass.dart';

/// 상세 화면 '거래 희망 장소' 카드: 작은 지도 + 물건 위치 핀 + 오른쪽 아래 '지도 보기'.
///  - 거래 장소 = 등록자 동네(물건 위치는 동네 안에서 살짝 흩어 놓은 점, 정확한 집 위치는 드러내지 않는다).
///  - 카카오맵 키가 없거나 지도를 못 띄우면 안내 문구가 있는 자리 표시를 보여 준다.
class TradePlaceCard extends StatefulWidget {
  const TradePlaceCard({super.key, required this.services, required this.item});

  final AppServices services;
  final Item item;

  @override
  State<TradePlaceCard> createState() => _TradePlaceCardState();
}

class _TradePlaceCardState extends State<TradePlaceCard> {
  late final Future<MapConfig> _config = MapConfig.load(widget.services.api);
  bool _failed = false;

  void _openFull() {
    Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => PlaceMapScreen(services: widget.services, item: widget.item)));
  }

  @override
  Widget build(BuildContext context) {
    final dong = placeName(widget.item);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      InkWell(
        onTap: _openFull,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(children: [
            Text('거래 희망 장소', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Sb.navy)),
            const SizedBox(width: 10),
            Expanded(
              child: Text(dong,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600, color: Sb.navy)),
            ),
            Icon(Icons.chevron_right_rounded, color: Sb.text2),
          ]),
        ),
      ),
      const SizedBox(height: 4),
      ClipRRect(
        borderRadius: BorderRadius.circular(16),
        child: SizedBox(
          height: 150,
          child: Stack(children: [
            Positioned.fill(
              child: FutureBuilder<MapConfig>(
                future: _config,
                builder: (context, snap) {
                  final c = snap.data;
                  if (c == null) return Container(color: Sb.bg);
                  if (!c.hasMap || _failed) return _NoMapKey(onTap: _openFull);
                  final at = itemLatLng(widget.item);
                  // 작은 지도는 끌기·확대를 막고, 누르면 전체 지도로 연다
                  return Stack(children: [
                    Positioned.fill(
                      child: IgnorePointer(
                        child: KakaoMapView(
                          jsKey: c.jsKeyForView,
                          center: at,
                          level: 4,
                          interactive: false,
                          pins: [MapPin(id: widget.item.id, at: at, plain: true)],
                          onError: (_) {
                            if (mounted) setState(() => _failed = true);
                          },
                        ),
                      ),
                    ),
                    Positioned.fill(
                      child: Material(type: MaterialType.transparency, child: InkWell(onTap: _openFull)),
                    ),
                  ]);
                },
              ),
            ),
            Positioned(
              right: 10,
              bottom: 10,
              child: Material(
                color: Sb.isDark ? const Color(0xE6111215) : const Color(0xF2FFFFFF),
                shape: const StadiumBorder(),
                child: InkWell(
                  customBorder: const StadiumBorder(),
                  onTap: _openFull,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                    child: Row(mainAxisSize: MainAxisSize.min, children: [
                      Text('지도 보기', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: Sb.navy)),
                      const SizedBox(width: 4),
                      Icon(Icons.open_in_full_rounded, size: 16, color: Sb.navy),
                    ]),
                  ),
                ),
              ),
            ),
          ]),
        ),
      ),
    ]);
  }
}

/// '망원동 근처' 처럼 거래 장소 이름
String placeName(Item item) {
  final region = item.owner.region.trim();
  final dong = region.isEmpty ? '동네' : region.split(' ').last;
  return '$dong 근처';
}

/// 지도 키가 없을 때 보여 주는 자리(시연 PC에 키를 넣으면 실제 지도로 바뀐다)
class _NoMapKey extends StatelessWidget {
  const _NoMapKey({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Sb.isDark ? Sb.card : Sb.bg,
      child: InkWell(
        onTap: onTap,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(Icons.map_outlined, color: Sb.text3, size: 32),
              const SizedBox(height: 6),
              Text('지도를 불러오지 못했어요 (인터넷 연결 확인)',
                  textAlign: TextAlign.center, style: TextStyle(fontSize: 13, color: Sb.text3)),
            ]),
          ),
        ),
      ),
    );
  }
}

/// '지도 보기': 거래 희망 장소를 전체 화면 지도로 본다(끌기·확대 가능)
class PlaceMapScreen extends StatefulWidget {
  const PlaceMapScreen({super.key, required this.services, required this.item});

  final AppServices services;
  final Item item;

  @override
  State<PlaceMapScreen> createState() => _PlaceMapScreenState();
}

class _PlaceMapScreenState extends State<PlaceMapScreen> {
  late final Future<MapConfig> _config = MapConfig.load(widget.services.api);
  bool _failed = false;

  @override
  Widget build(BuildContext context) {
    final at = itemLatLng(widget.item);
    final top = MediaQuery.of(context).padding.top;
    return Scaffold(
      body: Stack(children: [
        Positioned.fill(
          child: FutureBuilder<MapConfig>(
            future: _config,
            builder: (context, snap) {
              final c = snap.data;
              if (c == null) return const SizedBox.shrink();
              if (!c.hasMap || _failed) return _NoMapKey(onTap: () {});
              return KakaoMapView(
                jsKey: c.jsKeyForView,
                center: at,
                level: 3,
                me: dongLatLng(widget.services.session.user?.region ?? ''),
                pins: [MapPin(id: widget.item.id, at: at, plain: true)],
                onError: (_) {
                  if (mounted) setState(() => _failed = true);
                },
              );
            },
          ),
        ),
        Positioned(
          left: 12,
          right: 16,
          top: top + 10,
          child: Row(children: [
            GlassIconButton(
              icon: Icons.arrow_back_rounded,
              tooltip: '뒤로',
              onPressed: () => Navigator.of(context).maybePop(),
            ),
            const SizedBox(width: 10),
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
                      child: Text('거래 희망 장소 · ${placeName(widget.item)}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: Sb.navy, fontSize: 15, fontWeight: FontWeight.w800)),
                    ),
                    const SizedBox(width: 12),
                  ]),
                ),
              ),
            ),
          ]),
        ),
      ]),
    );
  }
}
