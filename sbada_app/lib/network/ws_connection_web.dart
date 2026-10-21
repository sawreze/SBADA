import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'ws_connection.dart';

/// 웹(브라우저)용: 브라우저 WebSocket.
/// 브라우저는 앱에서 ping 프레임을 보낼 수 없어서, 서버 프로토콜의 {"type":"ping"} 을 25초마다 보낸다.
Future<WsConnection> connectWs(String url) {
  final ws = web.WebSocket(url);
  final opened = Completer<WsConnection>();
  final messages = StreamController<String>();
  Timer? ping;

  ws.onopen = (web.Event _) {
    ping = Timer.periodic(const Duration(seconds: 25), (_) {
      if (ws.readyState == web.WebSocket.OPEN) ws.send('{"type":"ping"}'.toJS);
    });
    if (!opened.isCompleted) opened.complete(_WebConnection(ws, messages.stream));
  }.toJS;
  ws.onmessage = (web.MessageEvent e) {
    final data = e.data;
    if (data.isA<JSString>()) messages.add((data as JSString).toDart);
  }.toJS;
  ws.onerror = (web.Event _) {
    if (!opened.isCompleted) opened.completeError(StateError('WebSocket 연결 실패: $url'));
  }.toJS;
  ws.onclose = (web.CloseEvent _) {
    ping?.cancel();
    if (!opened.isCompleted) opened.completeError(StateError('WebSocket 이 열리기 전에 닫힘'));
    messages.close();
  }.toJS;
  return opened.future;
}

class _WebConnection implements WsConnection {
  _WebConnection(this._ws, this.messages);

  final web.WebSocket _ws;

  @override
  final Stream<String> messages;

  @override
  bool get isOpen => _ws.readyState == web.WebSocket.OPEN;

  @override
  void send(String text) => _ws.send(text.toJS);

  @override
  Future<void> close() async => _ws.close(1000);
}
