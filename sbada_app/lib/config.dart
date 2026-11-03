import 'package:flutter/foundation.dart';

/// 서버 주소는 실행 명령에서 받는다(10장 2.5):
///   flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8097
/// Android 에뮬레이터의 10.0.2.2 는 개발 PC 의 127.0.0.1 로 이어진다.
class AppConfig {
  static const _apiBaseUrlDefine = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8097',
  );

  /// 서버 주소. 웹 빌드에서 API_BASE_URL=ORIGIN 이면 '이 웹 화면을 내려 준 서버'(같은 주소)를 쓴다
  /// → 휴대폰 브라우저로 http://맥IP:8097/app/ 에 들어가도 그대로 동작(폰으로_보기.command)
  static final String apiBaseUrl =
      kIsWeb && _apiBaseUrlDefine == 'ORIGIN' ? Uri.base.origin : _apiBaseUrlDefine;

  /// http → ws, https → wss
  static String get wsUrl => '${apiBaseUrl.replaceFirst(RegExp('^http'), 'ws')}/ws';

  /// 서버가 돌려준 '/api/images/3' 같은 경로를 전체 주소로 바꾼다.
  static String imageUrl(String? path) {
    if (path == null || path.isEmpty) return '';
    if (path.startsWith('http')) return path;
    return '$apiBaseUrl$path';
  }
}
