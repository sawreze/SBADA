import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// 앱(Android)용: WebView 에 HTML 을 띄운다. 자바스크립트 채널 이름은 'Sbada'.
class MapHost {
  MapHost({required String html, required void Function(String message) onMessage}) {
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFF1B1E23))
      ..addJavaScriptChannel('Sbada', onMessageReceived: (m) => onMessage(m.message))
      // 지도 HTML 안의 링크를 눌러도 다른 사이트로 넘어가지 않게(넘어가면 지도가 사라진다)
      ..setNavigationDelegate(NavigationDelegate(
        onNavigationRequest: (r) => r.isMainFrame && !r.url.startsWith('http://localhost')
            ? NavigationDecision.prevent
            : NavigationDecision.navigate,
      ))
      ..loadHtmlString(html, baseUrl: 'http://localhost');
  }

  late final WebViewController _web;

  Future<void> run(String js) => _web.runJavaScript(js);

  Widget build() => WebViewWidget(controller: _web);

  void dispose() {}
}
