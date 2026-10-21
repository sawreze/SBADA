/// 지도 HTML 을 띄우는 그릇(플랫폼별).
///  - 앱(Android): webview_flutter WebView (map_host_io.dart)
///  - 웹(브라우저 테스트 화면): iframe (map_host_web.dart) — 브라우저에는 WebView 가 없어서
/// 조건부 export 라서 KakaoMapView 는 어느 쪽인지 몰라도 된다(ws_connection.dart 와 같은 방식).
/// 두 쪽 모두 HTML 안의 `Sbada.postMessage(문자열)` 을 [onMessage] 로 넘기고, [run] 으로 자바스크립트를 실행한다.
library;

export 'map_host_io.dart' if (dart.library.js_interop) 'map_host_web.dart';
