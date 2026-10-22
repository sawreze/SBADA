import 'dart:math' as math;

import '../models/item.dart';
import 'regions.dart';

export 'regions.dart';

/// 위도·경도 한 점
class LatLng {
  const LatLng(this.lat, this.lng);

  final double lat;
  final double lng;

  /// 두 점 사이 거리(km, 하버사인 공식)
  double kmTo(LatLng o) {
    const earthKm = 6371.0;
    final dLat = _rad(o.lat - lat);
    final dLng = _rad(o.lng - lng);
    final a = math.sin(dLat / 2) * math.sin(dLat / 2) +
        math.cos(_rad(lat)) * math.cos(_rad(o.lat)) * math.sin(dLng / 2) * math.sin(dLng / 2);
    return earthKm * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a));
  }

  static double _rad(double d) => d * math.pi / 180;
}

/// 시연 동네(마포구 일대) 중심 좌표. 실제 지도(카카오맵)에 핀을 꽂을 때 쓴다.
const Map<String, LatLng> dongCenters = {
  '상암동': LatLng(37.5790, 126.8895),
  '성산동': LatLng(37.5662, 126.9100),
  '연희동': LatLng(37.5690, 126.9310),
  '연남동': LatLng(37.5625, 126.9230),
  '망원동': LatLng(37.5560, 126.9030),
  '서교동': LatLng(37.5530, 126.9190),
  '합정동': LatLng(37.5495, 126.9135),
  '상수동': LatLng(37.5480, 126.9230),
};

/// 동네를 모를 때의 기준점(마포구청 근처)
const mapoCenter = LatLng(37.5663, 126.9019);

/// '서울 마포구 망원동' → 망원동 중심 좌표. 모르는 동네는 이름으로 정한 고정 위치(마포구 근처).
LatLng dongLatLng(String region) {
  final r = region.trim();
  final full = regionCenters[r];
  if (full != null) return full; // 전국 동네(V9): '부산 해운대구 우동' 처럼 지역 전체 이름으로 찾는다
  final near = _nearestKnown(r);
  if (near != null) return near; // 목록에 없는 동네: 같은 시·구(또는 시도) 동네 좌표
  final dong = r.isEmpty ? '' : r.split(' ').last;
  final known = dongCenters[dong];
  if (known != null) return known;
  if (dong.isEmpty) return mapoCenter;
  final h = dong.codeUnits.fold<int>(7, (a, c) => (a * 31 + c) & 0x7fffffff);
  return LatLng(mapoCenter.lat + ((h % 200) - 100) / 10000, mapoCenter.lng + (((h ~/ 200) % 200) - 100) / 10000);
}

/// 물건 위치 = 등록자 동네 중심 + 물건 번호로 정한 작은 흩어짐(170~370m).
/// 정확한 집 위치를 드러내지 않으려고(명세서 보안 항목) 동네 안에서만 흩어 놓는다.
LatLng itemLatLng(Item item) {
  final c = dongLatLng(item.owner.region);
  final angle = item.id * 2.39996; // 황금각: 번호가 이어져도 고르게 퍼진다
  final meters = 165.0 + (item.id * 37 % 70) * 3;
  final dLat = meters * math.sin(angle) / 111000;
  final dLng = meters * math.cos(angle) / (111000 * math.cos(c.lat * math.pi / 180));
  return LatLng(c.lat + dLat, c.lng + dLng);
}

/// '부산 해운대구 좌동' → '부산 해운대구 …' 동네 좌표, '해운대구 좌동' → '… 해운대구 …', '부산' → 부산 동네 하나.
/// 서울 마포구 시연 동네('마포구 망원동')는 아래 dongCenters 로 찾으므로 여기서는 건너뛴다.
LatLng? _nearestKnown(String region) {
  final words = region.split(' ').where((w) => w.isNotEmpty).toList();
  if (words.isEmpty || dongCenters.containsKey(words.last)) return null;
  final keys = regionCenters.keys;
  for (final n in [2, 1]) {
    if (words.length < n) continue;
    final prefix = words.take(n).join(' ');
    for (final k in keys) {
      if (k == prefix || k.startsWith('$prefix ')) return regionCenters[k];
    }
  }
  for (final k in keys) {
    final parts = k.split(' ');
    if (parts.length >= 3 && parts[1] == words.first) return regionCenters[k]; // 시도 없이 '수원시 …', '해운대구 …'
  }
  return null;
}

/// 동네 입력 후보: 전국 동네(regionCenters) + 서울 마포구 일대 시연 동네('마포구 망원동' 형식)
List<String> knownRegions() => [
      for (final d in dongCenters.keys) d == '연희동' ? '서대문구 $d' : '마포구 $d',
      ...regionCenters.keys,
    ];
