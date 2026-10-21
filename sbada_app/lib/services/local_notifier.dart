import 'package:flutter/services.dart';

/// Android 알림(상단 알림 표시줄)을 띄운다.
/// 패키지를 늘리지 않으려고 MainActivity.kt 의 NotificationManager 코드를 MethodChannel 로 부른다.
/// 테스트·데스크톱처럼 채널이 없으면 조용히 넘어간다.
class LocalNotifier {
  static const _channel = MethodChannel('sbada/notify');
  int _nextId = 1;

  /// Android 13 이상은 알림 권한(POST_NOTIFICATIONS)을 실행 중에 물어야 한다.
  Future<bool> requestPermission() async {
    try {
      return await _channel.invokeMethod<bool>('requestPermission') ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  Future<void> show(String title, String body) async {
    try {
      await _channel.invokeMethod<bool>('show', {'id': _nextId++, 'title': title, 'body': body});
    } on MissingPluginException {
      // 테스트 환경
    } on PlatformException {
      // 권한이 없으면 표시되지 않는다
    }
  }
}
