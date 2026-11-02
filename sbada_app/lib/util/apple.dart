import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 아이폰(iOS)일 때만 켜는 디자인(리퀴드 글래스 · 펼쳐지는 화면 전환 · 눌림 효과 · 햅틱).
/// 안드로이드는 지금 모습 그대로 둔다.
///
/// 웹 테스트 화면에서 iOS 모양을 미리 보려면 주소 뒤에 ?ios=1 을 붙인다(http://127.0.0.1:8097/app/?ios=1).
class Apple {
  Apple._();

  static bool _webPreview = false;

  /// main() 에서 한 번 부른다
  static void init() {
    if (kIsWeb) _webPreview = Uri.base.queryParameters['ios'] == '1';
  }

  /// 테스트에서 iOS 모양을 강제로 켤 때
  @visibleForTesting
  static bool? debugOverride;

  static bool get on => debugOverride ?? (_webPreview || (!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS));

  /// 가벼운 '톡' 진동(탭 이동 · 선택)
  static void tick() {
    if (on) HapticFeedback.selectionClick();
  }

  /// 조금 더 또렷한 진동(찜 · 완료)
  static void tap() {
    if (on) HapticFeedback.lightImpact();
  }
}
