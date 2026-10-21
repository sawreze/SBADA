import 'package:flutter/foundation.dart';

import '../network/api_client.dart';

/// 지도 설정(서버 GET /api/config/map). 키는 앱 소스가 아니라 서버의 application-local.properties 에 있다.
///  - provider = 'osm'(기본): OpenStreetMap 지도(Leaflet). 키 · 요금 없음
///  - provider = 'kakao'   : 카카오맵(유료 전환, 콘솔에서 카카오맵 사용 설정 필요) — 키가 있어야 쓴다
class MapConfig {
  const MapConfig({required this.provider, required this.kakaoJsKey});

  final String provider;
  final String? kakaoJsKey;

  /// 실제 지도를 띄울 수 있나. OSM 은 앱 · 웹(iframe) 모두. 카카오는 웹 주소가 콘솔 도메인에 없어 앱에서만.
  bool get hasMap => provider == 'osm' || (!kIsWeb && kakaoJsKey != null && kakaoJsKey!.isNotEmpty);

  /// 지도 웹뷰에 넘길 카카오 키. null 이면 OpenStreetMap 으로 그린다.
  String? get jsKeyForView => provider == 'kakao' ? kakaoJsKey : null;

  static Future<MapConfig>? _cache;

  /// 한 번만 서버에 묻고 기억한다. 실패하면(서버 꺼짐 등) OpenStreetMap 으로 본다(키가 필요 없으니까).
  static Future<MapConfig> load(ApiClient api) {
    return _cache ??= api.get('/api/config/map', auth: false).then((json) {
      final m = json as Map<String, dynamic>;
      return MapConfig(provider: (m['provider'] as String?) ?? 'osm', kakaoJsKey: m['kakaoJsKey'] as String?);
    }).catchError((Object _) {
      _cache = null; // 다음에 다시 시도
      return const MapConfig(provider: 'osm', kakaoJsKey: null);
    });
  }

  /// 테스트용
  static void resetForTest() => _cache = null;
}
