import 'dart:math' as math;

import '../models/item.dart';

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
  final dong = region.trim().isEmpty ? '' : region.trim().split(' ').last;
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
