/// 토스페이먼츠 결제창(2026-11-25). 앱(iOS · Android)은 WebView 로 토스 JS SDK 를 띄우고,
/// 웹(브라우저 테스트 화면)은 WebView 가 없어 '앱에서 결제해 주세요' 안내만 한다(조건부 export — map_host.dart 와 같은 방식).
library;

export 'toss_checkout_io.dart' if (dart.library.js_interop) 'toss_checkout_web.dart';
