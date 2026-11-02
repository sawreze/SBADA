import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../repositories/notification_repository.dart';

/// 앱이 백그라운드 · 종료 상태일 때 FCM 이 깨워서 부르는 함수(맨 바깥 함수여야 하고, 트리 셰이킹으로 지워지지 않게 표시).
/// 'notification' 이 들어 있는 메시지는 Android(FCM) · iOS(APNs)가 알림 표시줄에 직접 띄우므로 여기서 할 일은 없다.
@pragma('vm:entry-point')
Future<void> sbadaBackgroundMessage(RemoteMessage message) async {}

/// 푸시 상태(계정정보 > 알림 설정에 보여 준다)
enum PushState {
  /// 아직 확인 전
  unknown,

  /// 웹 테스트 화면은 푸시 대신 WebSocket 알림만
  unsupported,

  /// google-services.json / GoogleService-Info.plist 가 없어 Firebase 를 켜지 못함(앱은 그대로 동작)
  notConfigured,

  /// 사용자가 알림 권한을 거절함
  denied,

  /// 기기 토큰을 서버에 등록함(서버에 Firebase 서비스 계정까지 있으면 실제로 받을 수 있다)
  registered,

  /// 토큰을 받거나 등록하다 실패함(iOS 는 유료 Apple 계정의 APNs 키가 없으면 여기)
  failed,
}

/// 푸시 알림(1장 6.4 · 그림 1-10): 앱 서버 → FCM(Google) → Android / FCM → APNs(Apple) → iOS → 알림 표시줄.
///  ① [init] 앱 시작 때: Firebase 켜기 + 백그라운드 함수 등록(설정 파일이 없으면 조용히 끔)
///  ② [start] 로그인 뒤: 알림 권한 → FCM 기기 토큰 → POST /api/devices 로 서버에 등록(토큰이 바뀌면 다시 등록)
///  ③ 알림을 눌러 앱이 열리면 [opened] 로 알림 종류를 알려 홈 화면이 알맞은 탭 · 화면을 연다
///  ④ [stop] 로그아웃: 이 기기 토큰을 서버에서 지운다
/// 로그인 JWT 와 푸시용 기기 토큰은 서로 다른 값이다(10장 7.3).
class PushService {
  PushService(this.notifications);

  final NotificationRepository notifications;

  final ValueNotifier<PushState> state = ValueNotifier(PushState.unknown);

  /// 서버에 Firebase 서비스 계정이 설정돼 있어 실제로 보낼 수 있는지(POST /api/devices 응답 fcmEnabled)
  final ValueNotifier<bool> serverReady = ValueNotifier(false);

  /// 알림을 눌러 앱이 열렸을 때의 데이터(type · refType · refId). 홈 화면이 듣고 이동한다
  final ValueNotifier<Map<String, dynamic>?> opened = ValueNotifier(null);

  static bool _firebaseReady = false;
  String? _token;
  StreamSubscription<String>? _refreshSub;
  StreamSubscription<RemoteMessage>? _openSub;

  /// main() 에서 runApp 전에 한 번. 설정 파일이 없으면 false(예외를 밖으로 내지 않는다)
  static Future<bool> init() async {
    if (kIsWeb || _firebaseReady) return _firebaseReady;
    try {
      await Firebase.initializeApp();
      FirebaseMessaging.onBackgroundMessage(sbadaBackgroundMessage);
      _firebaseReady = true;
    } catch (e) {
      debugPrint('FCM 꺼짐(설정 파일 없음): $e');
    }
    return _firebaseReady;
  }

  /// 아이폰 APNs 직접 연결(ios/Runner/AppDelegate.swift 의 'sbada/apns')
  static const _apns = MethodChannel('sbada/apns');

  /// 서버에 등록할 platform 값(서버 ApnsPushSender.PLATFORM 과 같아야 한다)
  static const apnsPlatform = 'ios-apns';

  /// 아이폰은 Firebase 없이 APNs 로 직접 받는다
  bool get _useApns => !kIsWeb && defaultTargetPlatform == TargetPlatform.iOS;

  /// 로그인 뒤 홈이 열릴 때. 실패해도 앱은 WebSocket 알림으로 계속 동작한다.
  Future<void> start() async {
    if (kIsWeb) {
      state.value = PushState.unsupported;
      return;
    }
    if (_useApns) return _startApns();
    if (!_firebaseReady) {
      state.value = PushState.notConfigured;
      return;
    }
    try {
      final fm = FirebaseMessaging.instance;
      final perm = await fm.requestPermission();
      if (perm.authorizationStatus == AuthorizationStatus.denied) {
        state.value = PushState.denied;
        return;
      }
      // 서버(FcmPushSender)는 FCM HTTP v1 의 message.token 으로 보내므로 '등록 토큰'을 받는다.
      // firebase_messaging 17 의 register()/onRegistered 는 설치 ID(FID)를 주는 다른 방식이라 쓰지 않는다.
      // ignore: deprecated_member_use
      final token = await fm.getToken();
      if (token == null) {
        state.value = PushState.failed;
        return;
      }
      await _register(token);
      // ignore: deprecated_member_use
      _refreshSub ??= fm.onTokenRefresh.listen((t) => unawaited(_register(t)));
      // 알림을 눌러 앱이 열림: 백그라운드였으면 onMessageOpenedApp, 완전히 꺼져 있었으면 getInitialMessage
      _openSub ??= FirebaseMessaging.onMessageOpenedApp.listen((m) => opened.value = m.data);
      final initial = await fm.getInitialMessage();
      if (initial != null) opened.value = initial.data;
    } catch (e) {
      debugPrint('FCM 토큰 등록 실패: $e');
      state.value = PushState.failed;
    }
  }

  /// 아이폰: 알림 권한 → APNs 기기 토큰 → POST /api/devices {platform: ios-apns}.
  /// 알림을 누르면 iOS 가 'opened' 로 알려 주고, 꺼진 앱이 푸시로 켜졌으면 initialOpened 로 가져온다.
  Future<void> _startApns() async {
    _apns.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'token': // 토큰이 바뀜(앱 재설치 · 복원 등) → 다시 등록
          final t = call.arguments as String?;
          if (t != null && t.isNotEmpty) await _register(t, apnsPlatform);
        case 'opened':
          opened.value = Map<String, dynamic>.from(call.arguments as Map);
      }
    });
    try {
      final initial = await _apns.invokeMethod<Map<Object?, Object?>>('initialOpened');
      if (initial != null && initial.isNotEmpty) opened.value = initial.map((k, v) => MapEntry('$k', v));
    } catch (_) {}
    try {
      final token = await _apns.invokeMethod<String>('register');
      if (token == null || token.isEmpty) {
        state.value = PushState.failed;
        return;
      }
      await _register(token, apnsPlatform);
    } on PlatformException catch (e) {
      debugPrint('APNs 등록 실패: ${e.code} ${e.message}');
      state.value = e.code == 'PERMISSION_DENIED' ? PushState.denied : PushState.failed;
    } on MissingPluginException {
      state.value = PushState.failed;
    }
  }

  Future<void> _register(String token, [String? platform]) async {
    _token = token;
    serverReady.value = await notifications.registerDevice(
        token, platform ?? (defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android'));
    state.value = PushState.registered;
  }

  /// 로그아웃: 서버에서 이 기기 토큰을 지운다(실패해도 로그아웃은 계속)
  Future<void> stop() async {
    final t = _token;
    _token = null;
    if (t != null) {
      try {
        await notifications.unregisterDevice(t);
      } catch (_) {}
    }
    state.value = PushState.unknown;
  }

  /// 화면에 보여 줄 한 줄 설명
  String get description => switch (state.value) {
        PushState.registered => serverReady.value
            ? (_useApns ? '앱이 꺼져 있어도 푸시로 받아요(APNs)' : '앱이 꺼져 있어도 푸시로 받아요(FCM)')
            : (_useApns
                ? '기기 등록은 됐어요 · 서버 APNs 키 설정 전이라 앱이 켜져 있을 때만 받아요'
                : '기기 등록은 됐어요 · 서버 Firebase 설정 전이라 앱이 켜져 있을 때만 받아요'),
        PushState.notConfigured => 'Firebase 설정 전이라 앱이 켜져 있을 때만 받아요',
        PushState.denied => '알림 권한이 꺼져 있어요. 휴대폰 설정에서 켜 주세요',
        PushState.unsupported => '웹 화면은 앱이 켜져 있을 때만 받아요',
        PushState.failed => _useApns
            ? 'APNs 등록에 실패했어요(유료 Apple 개발자 계정 · Push 권한 필요). 앱이 켜져 있을 때만 받아요'
            : '푸시 등록에 실패했어요. 앱이 켜져 있을 때만 받아요',
        PushState.unknown => '확인 중…',
      };
}
