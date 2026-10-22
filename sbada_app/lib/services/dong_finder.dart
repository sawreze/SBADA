import 'dart:convert';

import '../map/geo.dart';
import '../network/api_client.dart';
import '../network/session.dart';
import 'location/location.dart';

/// GPS 로 찾은 내 동네. region = '서울 마포구 망원동'(app_users.region 과 같은 모양), dong = '망원동'(화면 표시)
typedef FoundDong = ({String region, String dong, LatLng at});

/// GPS → 서버 GET /api/geo/reverse → 동네 이름.
///  - 화면에는 지금처럼 '동' 이름만 보인다(홈 왼쪽 위 '망원동'). 서버에는 동네 이름만 저장하고 좌표는 저장하지 않는다
///  - 찾은 좌표는 이 기기에만 기억해서(sbada.gps.centers) 지도의 '내 동네' 중심을 그 자리로 맞춘다
///    (지도 동네 목록 regions.dart 에 없는 동네도 정확한 자리에 표시)
class DongFinder {
  static const _key = 'sbada.gps.centers';

  /// 위치 권한 · GPS · 주소 서버 오류는 LocationException / ApiException 으로 올라간다(화면이 안내 문구로 보여 줌)
  static Future<FoundDong> find(ApiClient api, KeyValueStore store) async {
    final p = await currentPosition();
    final j = await api.get('/api/geo/reverse',
        query: {'lat': p.lat.toStringAsFixed(6), 'lng': p.lng.toStringAsFixed(6)}, auth: false) as Map<String, dynamic>;
    final found = (region: j['region'] as String, dong: j['dong'] as String, at: LatLng(p.lat, p.lng));
    gpsRegionCenters[found.region] = found.at;
    await store.write(_key, jsonEncode({
      for (final e in gpsRegionCenters.entries) e.key: [e.value.lat, e.value.lng],
    }));
    return found;
  }

  /// 앱 시작 때 기억해 둔 GPS 동네 좌표를 읽는다
  static Future<void> load(KeyValueStore store) async {
    try {
      final raw = await store.read(_key);
      if (raw == null) return;
      final m = jsonDecode(raw) as Map<String, dynamic>;
      for (final e in m.entries) {
        final v = e.value as List<dynamic>;
        gpsRegionCenters[e.key] = LatLng((v[0] as num).toDouble(), (v[1] as num).toDouble());
      }
    } catch (_) {
      // 깨진 값이면 무시(동네 목록 좌표를 쓴다)
    }
  }
}
