/// 지도(iframe) 위에 떠 있는 버튼 · 탭바가 눌리게 하는 덮개.
///
/// 웹(브라우저 테스트 화면)에서는 지도가 iframe 이라, 그 위에 Flutter 버튼을 그려도
/// 마우스 클릭이 iframe 으로 빨려 들어가 Flutter 까지 오지 않는다(버튼 · 하단 탭바가 먹통).
/// 웹에서는 버튼 뒤에 빈 HTML div 를 하나 깔아서 클릭을 그 div 가 받게 하고(그러면 Flutter 로 전달된다),
/// 앱(Android/iOS)에서는 아무것도 하지 않고 child 를 그대로 돌려준다.
library;

export 'pointer_shield_io.dart' if (dart.library.js_interop) 'pointer_shield_web.dart';
