/// GPS 로 지금 위치 한 번 읽기(플랫폼별).
///  - 앱(Android · iOS): MethodChannel("sbada/location") → MainActivity.kt / AppDelegate.swift (패키지 없이)
///  - 웹(브라우저 테스트 화면): 브라우저 위치(navigator.geolocation) — localhost 에서는 http 여도 동작
/// 조건부 export 라서 부르는 쪽은 어느 쪽인지 몰라도 된다(ws_connection.dart 와 같은 방식).
library;

export 'location_io.dart' if (dart.library.js_interop) 'location_web.dart';

/// 위도 · 경도
typedef Position = ({double lat, double lng});

/// 위치를 못 읽었을 때. code = PERMISSION_DENIED · LOCATION_OFF · TIMEOUT · UNSUPPORTED
class LocationException implements Exception {
  const LocationException(this.code);

  final String code;

  /// 화면에 보여 줄 안내
  String get message => switch (code) {
        'PERMISSION_DENIED' => '위치 권한이 꺼져 있어요. 설정에서 스바다의 위치 권한을 허용하거나 동네를 직접 입력해 주세요.',
        'LOCATION_OFF' => '휴대폰의 위치(GPS)를 켜고 다시 눌러 주세요.',
        'UNSUPPORTED' => '이 기기에서는 위치를 쓸 수 없어요. 동네를 직접 입력해 주세요.',
        _ => '위치를 찾지 못했어요. 잠시 뒤 다시 누르거나 동네를 직접 입력해 주세요.',
      };

  @override
  String toString() => 'LocationException($code)';
}
