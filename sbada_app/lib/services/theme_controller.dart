import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../theme/app_theme.dart';

/// 다크 / 화이트 모드 전환.
///  - 고른 모드는 기기(shared_preferences)에 저장해 앱을 다시 켜도 유지한다.
///  - 색 토큰(Sb.*)이 모드에 따라 값이 바뀌므로, 바꾼 순간 화면 전체를 다시 그린다.
class ThemeController {
  ThemeController._();

  static const _key = 'sbada.theme.dark';

  /// main() 에서 첫 화면을 그리기 전에 부른다.
  static Future<void> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      Sb.darkMode.value = prefs.getBool(_key) ?? true;
    } catch (_) {
      // 저장소를 못 읽으면 기본값(다크)
    }
  }

  static Future<void> setDark(bool dark) async {
    if (Sb.darkMode.value == dark) return;
    Sb.darkMode.value = dark;
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_key, dark);
    } catch (_) {
      // 저장 실패는 이번 실행에만 적용되고 넘어간다
    }
  }

  static Future<void> toggle() => setDark(!Sb.darkMode.value);

  /// 앱의 모든 화면(쌓여 있는 이전 화면·탭 포함)을 다시 그리게 한다.
  static void rebuildAll(BuildContext root) {
    void mark(Element e) {
      e.markNeedsBuild();
      e.visitChildren(mark);
    }

    (root as Element).visitChildren(mark);
  }
}
