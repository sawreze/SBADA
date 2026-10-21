import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'session.dart';
import 'ws_connection.dart';

/// WebSocket 연결(1장: 연결을 유지하는 동안 양쪽이 아무 때나 보낸다).
///  - 주소: ws://10.0.2.2:8097/ws?token=JWT
///  - 끊기면 1, 2, 4, 8, 16, 30초 간격으로 다시 연결한다(지수 백오프)
///  - 25초마다 ping 으로 연결이 살아 있는지 확인한다
class RealtimeClient {
  RealtimeClient({required this.wsUrl, required this.session});

  final String wsUrl;
  final Session session;

  final _events = StreamController<Map<String, dynamic>>.broadcast();
  final ValueNotifier<bool> connected = ValueNotifier(false);

  WsConnection? _ws;
  Timer? _retryTimer;
  int _attempt = 0;
  bool _wanted = false;
  bool _connecting = false;

  /// 서버가 보낸 이벤트: chat.message · chat.read · notification · exchange.updated · error · hello
  Stream<Map<String, dynamic>> get events => _events.stream;

  void connect() {
    _wanted = true;
    if (_ws == null) _open();
  }

  Future<void> _open() async {
    final token = session.token;
    if (!_wanted || token == null || _connecting) return;
    _connecting = true;
    try {
      // 앱은 dart:io, 웹은 브라우저 WebSocket(ws_connection.dart). 둘 다 25초마다 연결 확인 ping 을 보낸다.
      final ws = await connectWs('$wsUrl?token=${Uri.encodeQueryComponent(token)}')
          .timeout(const Duration(seconds: 8));
      _ws = ws;
      _attempt = 0;
      connected.value = true;
      ws.messages.listen(
        (data) {
          try {
            final decoded = jsonDecode(data);
            if (decoded is Map<String, dynamic>) _events.add(decoded);
          } catch (_) {
            // 형식이 틀린 메시지는 무시
          }
        },
        onDone: () => _closed(ws),
        onError: (Object _) => _closed(ws),
        cancelOnError: true,
      );
    } catch (_) {
      _scheduleRetry();
    } finally {
      _connecting = false;
    }
  }

  void _closed(WsConnection ws) {
    if (!identical(_ws, ws)) return; // 이미 처리한 연결
    _ws = null;
    connected.value = false;
    _scheduleRetry();
  }

  void _scheduleRetry() {
    if (!_wanted) return;
    _retryTimer?.cancel();
    final seconds = min(30, 1 << min(_attempt, 5));
    _attempt++;
    _retryTimer = Timer(Duration(seconds: seconds), _open);
  }

  /// 연결되어 있으면 보내고 true. 아니면 false(호출한 쪽이 REST 로 대신 보낸다).
  bool send(Map<String, dynamic> message) {
    final ws = _ws;
    if (ws == null || !ws.isOpen) return false;
    ws.send(jsonEncode(message));
    return true;
  }

  Future<void> disconnect() async {
    _wanted = false;
    _retryTimer?.cancel();
    final ws = _ws;
    _ws = null;
    connected.value = false;
    await ws?.close();
  }
}
