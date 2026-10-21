/// 플랫폼별 WebSocket 연결.
///  - 앱(Android): dart:io WebSocket (ws_connection_io.dart)
///  - 웹(브라우저 테스트 화면): 브라우저 WebSocket (ws_connection_web.dart)
/// 조건부 export 라서 RealtimeClient 는 어느 쪽인지 몰라도 된다.
library;

export 'ws_connection_io.dart' if (dart.library.js_interop) 'ws_connection_web.dart';

/// 연결 하나(문자열 메시지만 주고받는다)
abstract class WsConnection {
  /// 서버가 보낸 메시지. 연결이 끊기면 스트림이 끝난다(onDone).
  Stream<String> get messages;

  bool get isOpen;

  void send(String text);

  Future<void> close();
}
