import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';

import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

/// 웹(브라우저 테스트 화면)용: iframe(srcdoc)에 HTML 을 띄운다.
///  - HTML → 앱: iframe 안에 Sbada.postMessage 가 없으므로 [_bridge] 가 만들어 parent.postMessage 로 보낸다.
///  - 앱 → HTML: iframe 으로 {sbadaJs: 코드} 메시지를 보내면 [_bridge] 가 실행한다(보낸 쪽이 부모 창일 때만).
class MapHost {
  MapHost({required String html, required void Function(String message) onMessage}) {
    _frame = web.HTMLIFrameElement()
      ..setAttribute('sandbox', 'allow-scripts allow-same-origin')
      ..srcdoc = html.replaceFirst('<head>', '<head><script>$_bridge</script>').toJS
      ..style.border = '0'
      ..style.width = '100%'
      ..style.height = '100%';
    _listener = (web.MessageEvent e) {
      if (e.source != _frame.contentWindow) return; // 다른 지도 · 다른 창의 메시지는 무시
      final data = e.data;
      if (data == null || !data.isA<JSObject>()) return;
      final msg = (data as JSObject).getProperty<JSAny?>('sbadaMsg'.toJS);
      if (msg != null && msg.isA<JSString>()) onMessage((msg as JSString).toDart);
    }.toJS;
    web.window.addEventListener('message', _listener);
  }

  late final web.HTMLIFrameElement _frame;
  late final JSFunction _listener;

  static const _bridge = '''
if (!window.Sbada) {
  window.Sbada = { postMessage: function(s){ parent.postMessage({sbadaMsg: s}, '*'); } };
  window.addEventListener('message', function(e){
    if (e.source === parent && e.data && typeof e.data.sbadaJs === 'string') { (0, eval)(e.data.sbadaJs); }
  });
}
''';

  Future<void> run(String js) async {
    final target = _frame.contentWindow;
    if (target == null) return;
    final payload = JSObject()..setProperty('sbadaJs'.toJS, js.toJS);
    target.postMessage(payload, '*'.toJS);
  }

  late final Widget _view = HtmlElementView.fromTagName(
        tagName: 'div',
        onElementCreated: (Object el) {
          final div = el as web.HTMLElement;
          div.style.width = '100%';
          div.style.height = '100%';
          div.append(_frame);
        },
      );

  Widget build() => _view;

  void dispose() => web.window.removeEventListener('message', _listener);
}
