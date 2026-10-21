import 'dart:io';

import 'ws_connection.dart';

/// 앱(Android)용: dart:io WebSocket. 25초마다 ping 으로 연결이 살아 있는지 확인한다.
Future<WsConnection> connectWs(String url) async {
  final ws = await WebSocket.connect(url);
  ws.pingInterval = const Duration(seconds: 25);
  return _IoConnection(ws);
}

class _IoConnection implements WsConnection {
  _IoConnection(this._ws) : messages = _ws.where((d) => d is String).cast<String>();

  final WebSocket _ws;

  @override
  final Stream<String> messages;

  @override
  bool get isOpen => _ws.readyState == WebSocket.open;

  @override
  void send(String text) => _ws.add(text);

  @override
  Future<void> close() => _ws.close(WebSocketStatus.normalClosure);
}
