import 'package:flutter/services.dart';
import 'package:kakao_flutter_sdk_user/kakao_flutter_sdk_user.dart';

/// 카카오 로그인에서 사용자에게 보여 줄 안내(취소는 예외가 아니라 null 로 처리)
class KakaoLoginException implements Exception {
  KakaoLoginException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// 카카오 SDK 로 로그인해서 '카카오 액세스 토큰'을 받는다.
/// 받은 토큰은 서버(POST /api/auth/kakao)로 보내고, 서버가 카카오에 확인한 뒤 스바다 JWT 를 준다.
///
/// 네이티브 앱 키는 소스에 넣지 않는다:
///   sbada_app/android/local.properties(Git 제외)의 kakao.nativeAppKey
///   → build.gradle.kts 가 AndroidManifest 에 넣음 → MainActivity 가 MethodChannel("sbada/config")로 전달
class KakaoLogin {
  KakaoLogin._();

  static const _config = MethodChannel('sbada/config');
  static bool _initialized = false;

  static Future<String?> _nativeAppKey() async {
    try {
      final key = await _config.invokeMethod<String>('kakaoNativeAppKey');
      return key == null || key.trim().isEmpty ? null : key.trim();
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// 카카오톡이 있으면 카카오톡으로, 없거나 실패하면 카카오계정(브라우저)으로 로그인한다.
  /// 사용자가 취소하면 null.
  static Future<String?> obtainAccessToken() async {
    final key = await _nativeAppKey();
    if (key == null) {
      throw KakaoLoginException('카카오 앱 키가 설정되지 않았어요.\n'
          'sbada_app/android/local.properties 에 kakao.nativeAppKey 를 넣고 앱을 다시 빌드해 주세요.');
    }
    if (!_initialized) {
      await KakaoSdk.init(nativeAppKey: key);
      _initialized = true;
    }

    if (await isKakaoTalkInstalled()) {
      try {
        final token = await UserApi.instance.loginWithKakaoTalk();
        return token.accessToken;
      } catch (e) {
        // 카카오톡 동의 화면에서 '취소'를 누른 경우: 카카오계정 로그인으로 넘어가지 않고 멈춘다
        if (_isCancel(e)) return null;
        // 카카오톡에 계정이 연결돼 있지 않은 경우 등 → 아래 카카오계정 로그인으로
      }
    }
    try {
      final token = await UserApi.instance.loginWithKakaoAccount();
      return token.accessToken;
    } catch (e) {
      if (_isCancel(e)) return null;
      throw KakaoLoginException('카카오 로그인에 실패했어요. ($e)');
    }
  }

  static bool _isCancel(Object e) =>
      (e is PlatformException && e.code == 'CANCELED') ||
      (e is KakaoAuthException && e.error == AuthErrorCause.accessDenied);
}
