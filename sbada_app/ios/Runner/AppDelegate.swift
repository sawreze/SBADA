import Flutter
import UIKit
import UserNotifications

/// iOS 쪽 연결 코드. Android 의 MainActivity.kt 와 같은 일을 한다(Dart 코드는 두 OS 가 같다).
///  - MethodChannel("sbada/notify"): 서버 → (WebSocket 'notification') → Dart → 여기 → iOS 알림 배너
///      requestPermission → 알림 권한 묻기(처음 한 번), show {id, title, body} → 바로 알림 표시
///  - MethodChannel("sbada/config"): kakaoNativeAppKey → 카카오 네이티브 앱 키
///      키는 소스에 없다: ios/Flutter/Secrets.xcconfig(Git 제외) → Info.plist 'KakaoNativeAppKey' → 여기
///  - 앱이 켜져 있을 때도 알림 배너가 보이게 UNUserNotificationCenter 의 delegate 를 맡는다
///    (Android 와 같은 동작: 앱 사용 중 교환 요청 · 채팅 알림을 위에 띄움)
@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    UNUserNotificationCenter.current().delegate = self
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    guard let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "SbadaChannels") else { return }
    let messenger = registrar.messenger()

    FlutterMethodChannel(name: "sbada/notify", binaryMessenger: messenger).setMethodCallHandler { call, result in
      switch call.method {
      case "requestPermission":
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
          DispatchQueue.main.async { result(granted) }
        }
      case "show":
        let args = call.arguments as? [String: Any] ?? [:]
        let content = UNMutableNotificationContent()
        content.title = args["title"] as? String ?? "스바다"
        content.body = args["body"] as? String ?? ""
        content.sound = .default
        let id = "sbada-\(args["id"] as? Int ?? 0)-\(Date().timeIntervalSince1970)"
        // trigger 가 nil 이면 바로 보낸다. 권한이 없으면 iOS 가 조용히 무시한다(Android 와 같음)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil)) { error in
          DispatchQueue.main.async { result(error == nil) }
        }
      default:
        result(FlutterMethodNotImplemented)
      }
    }

    FlutterMethodChannel(name: "sbada/config", binaryMessenger: messenger).setMethodCallHandler { call, result in
      switch call.method {
      case "kakaoNativeAppKey":
        result(AppDelegate.kakaoNativeAppKey())
      default:
        result(FlutterMethodNotImplemented)
      }
    }
  }

  /// Info.plist 의 KakaoNativeAppKey. Secrets.xcconfig 가 없으면 "$(KAKAO_NATIVE_APP_KEY)" 그대로이거나 빈 값 → "" 로 돌려준다
  /// (Dart 쪽은 빈 키면 카카오 로그인을 쓰지 않는다 — Android 와 같음)
  private static func kakaoNativeAppKey() -> String {
    let raw = (Bundle.main.object(forInfoDictionaryKey: "KakaoNativeAppKey") as? String ?? "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    return raw.contains("$(") ? "" : raw
  }

  /// 앱이 켜져 있을 때 온 알림도 배너로 보여 준다
  override func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    completionHandler([.banner, .list, .sound])
  }
}
