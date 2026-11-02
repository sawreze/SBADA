import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/user.dart';

/// 키-값 저장소. 앱에서는 SharedPreferences, 테스트에서는 메모리를 쓴다.
abstract class KeyValueStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> remove(String key);
}

class PrefsStore implements KeyValueStore {
  final SharedPreferencesAsync _prefs = SharedPreferencesAsync();

  @override
  Future<String?> read(String key) => _prefs.getString(key);

  @override
  Future<void> write(String key, String value) => _prefs.setString(key, value);

  @override
  Future<void> remove(String key) => _prefs.remove(key);
}

class MemoryStore implements KeyValueStore {
  final Map<String, String> data = {};

  @override
  Future<String?> read(String key) async => data[key];

  @override
  Future<void> write(String key, String value) async {
    data[key] = value;
  }

  @override
  Future<void> remove(String key) async {
    data.remove(key);
  }
}

/// 로그인 상태(토큰 + 사용자). 토큰은 보호 요청에만 붙인다.
///
/// 주의(10장 4.4): SharedPreferences 는 암호화 저장소가 아니다. 이 앱은 시연용으로
/// 토큰 유효시간(2시간)을 짧게 두고, 로그아웃하면 지운다. 실서비스라면 Android Keystore 기반
/// 보안 저장소와 갱신 토큰 정책을 따로 설계한다.
class Session extends ChangeNotifier {
  Session(this._store);

  static const _tokenKey = 'sbada.token';
  static const _userKey = 'sbada.user';
  static const onboardingDoneKey = 'sbada.onboarding_done';
  static const pendingPrefsKey = 'sbada.pending_prefs';
  /// 처음 쓸 때 온보딩에서 고른 사는 동네(로그인 전) → 로그인 뒤 PUT /api/me/region
  static const pendingRegionKey = 'sbada.pending_region';

  /// 로그인 화면 '로그인 유지' 체크 상태(기기에 기억, 처음엔 켜짐)
  static const keepLoginKey = 'sbada.keep_login';

  final KeyValueStore _store;
  String? _token;
  AppUser? _user;

  /// false 면 이번 로그인은 기기에 저장하지 않는다(앱을 껐다 켜면 다시 로그인)
  bool _persist = true;

  String? get token => _token;
  AppUser? get user => _user;
  bool get isLoggedIn => _token != null && _user != null;
  KeyValueStore get store => _store;

  Future<void> load() async {
    _token = await _store.read(_tokenKey);
    final raw = await _store.read(_userKey);
    if (raw != null) {
      try {
        _user = AppUser.fromJson(jsonDecode(raw) as Map<String, dynamic>);
      } catch (_) {
        _user = null;
      }
    }
  }

  /// [persist] = '로그인 유지'. false 면 토큰을 메모리에만 두고 기기에 남은 이전 로그인도 지운다.
  Future<void> signIn(String token, AppUser user, {bool persist = true}) async {
    _token = token;
    _user = user;
    _persist = persist;
    if (persist) {
      await _store.write(_tokenKey, token);
      await _store.write(_userKey, jsonEncode(user.toJson()));
    } else {
      await _store.remove(_tokenKey);
      await _store.remove(_userKey);
    }
    notifyListeners();
  }

  Future<void> updateUser(AppUser user) async {
    _user = user;
    if (_persist) await _store.write(_userKey, jsonEncode(user.toJson()));
    notifyListeners();
  }

  /// 로그인 화면 체크박스의 마지막 선택(처음엔 true)
  Future<bool> keepLogin() async => (await _store.read(keepLoginKey)) != 'false';

  Future<void> setKeepLogin(bool on) => _store.write(keepLoginKey, on ? 'true' : 'false');

  Future<void> signOut() async {
    _token = null;
    _user = null;
    _persist = true;
    await _store.remove(_tokenKey);
    await _store.remove(_userKey);
    notifyListeners();
  }
}
