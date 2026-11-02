import Flutter
import UIKit
import UserNotifications
import CoreLocation

/// iOS 쪽 연결 코드. Android 의 MainActivity.kt 와 같은 일을 한다(Dart 코드는 두 OS 가 같다).
///  - MethodChannel("sbada/notify"): 서버 → (WebSocket 'notification') → Dart → 여기 → iOS 알림 배너
///      requestPermission → 알림 권한 묻기(처음 한 번), show {id, title, body} → 바로 알림 표시
///  - MethodChannel("sbada/config"): kakaoNativeAppKey → 카카오 네이티브 앱 키
///      키는 소스에 없다: ios/Flutter/Secrets.xcconfig(Git 제외) → Info.plist 'KakaoNativeAppKey' → 여기
///  - MethodChannel("sbada/location"): current → GPS 로 지금 위치 {lat, lng} 한 번(SbadaLocator, 패키지 없이 CoreLocation)
///  - MethodChannel("sbada/apns"): Apple APNs 직접 푸시(Firebase 없이)
///      register → 알림 권한 → registerForRemoteNotifications → APNs 기기 토큰(64자리 16진수) 을 돌려준다
///      initialOpened → 앱이 꺼져 있다가 푸시를 눌러 켜졌으면 그 알림의 값 {type, refType, refId}
///      (iOS → Dart) token: 토큰이 바뀌면 다시 알림 · opened: 알림을 누름
///  - 앱이 켜져 있을 때도 알림 배너가 보이게 UNUserNotificationCenter 의 delegate 를 맡는다
///    (Android 와 같은 동작: 앱 사용 중 교환 요청 · 채팅 알림을 위에 띄움)
@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate {
  private let locator = SbadaLocator()
  private var apns: FlutterMethodChannel?
  private var pendingToken: FlutterResult?
  private var lastToken: String?
  private var launchPush: [String: String]?

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    UNUserNotificationCenter.current().delegate = self
    // 꺼진 앱을 푸시를 눌러 켰을 때(옛 방식 경로 — 새 방식은 아래 didReceive 로 온다)
    if let info = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
      launchPush = AppDelegate.appData(info)
    }
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

    let apnsChannel = FlutterMethodChannel(name: "sbada/apns", binaryMessenger: messenger)
    apns = apnsChannel
    apnsChannel.setMethodCallHandler { [weak self] call, result in
      guard let self = self else { result(nil); return }
      switch call.method {
      case "register":
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
          DispatchQueue.main.async {
            guard granted else {
              result(FlutterError(code: "PERMISSION_DENIED", message: "알림 권한이 없어요", details: nil)); return
            }
            if let t = self.lastToken { result(t); return }
            self.pendingToken?(nil)
            self.pendingToken = result
            UIApplication.shared.registerForRemoteNotifications()
            // 15초 안에 토큰이 안 오면(네트워크 · 서명 문제) 실패로 끝낸다
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) {
              if let pending = self.pendingToken {
                self.pendingToken = nil
                pending(FlutterError(code: "APNS_TIMEOUT", message: "APNs 토큰을 받지 못했어요", details: nil))
              }
            }
          }
        }
      case "initialOpened":
        result(self.launchPush)
        self.launchPush = nil
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

    // GPS: 지금 위치(위도 · 경도) 한 번 읽기 → Dart 가 서버 /api/geo/reverse 로 동네 이름을 찾는다(Android 와 같은 결과 · 오류 코드)
    FlutterMethodChannel(name: "sbada/location", binaryMessenger: messenger).setMethodCallHandler { [weak self] call, result in
      guard call.method == "current", let self = self else { result(FlutterMethodNotImplemented); return }
      self.locator.current(result)
    }
  }

  /// Info.plist 의 KakaoNativeAppKey. Secrets.xcconfig 가 없으면 "$(KAKAO_NATIVE_APP_KEY)" 그대로이거나 빈 값 → "" 로 돌려준다
  /// (Dart 쪽은 빈 키면 카카오 로그인을 쓰지 않는다 — Android 와 같음)
  private static func kakaoNativeAppKey() -> String {
    let raw = (Bundle.main.object(forInfoDictionaryKey: "KakaoNativeAppKey") as? String ?? "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    return raw.contains("$(") ? "" : raw
  }

  /// APNs 가 기기 토큰을 줬다: Data → 16진수 문자열(서버가 https://api.push.apple.com/3/device/{이 값} 으로 보낸다)
  override func application(_ application: UIApplication,
                             didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    let token = deviceToken.map { String(format: "%02x", $0) }.joined()
    let changed = lastToken != nil && lastToken != token
    lastToken = token
    if let pending = pendingToken {
      pendingToken = nil
      pending(token)
    } else if changed {
      apns?.invokeMethod("token", arguments: token)
    }
    super.application(application, didRegisterForRemoteNotificationsWithDeviceToken: deviceToken)
  }

  override func application(_ application: UIApplication,
                             didFailToRegisterForRemoteNotificationsWithError error: Error) {
    if let pending = pendingToken {
      pendingToken = nil
      pending(FlutterError(code: "APNS_FAILED", message: error.localizedDescription, details: nil))
    }
    super.application(application, didFailToRegisterForRemoteNotificationsWithError: error)
  }

  /// 알림을 눌렀다: 앱 데이터(type · refType · refId)를 Dart 에 넘겨 알맞은 화면을 연다
  override func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    let data = AppDelegate.appData(response.notification.request.content.userInfo)
    if data["type"] != nil {
      if let channel = apns {
        channel.invokeMethod("opened", arguments: data)
      } else {
        launchPush = data // Flutter 가 아직 준비 전 → initialOpened 로 가져간다
      }
    }
    super.userNotificationCenter(center, didReceive: response, withCompletionHandler: completionHandler)
  }

  /// 푸시 본문에서 aps 를 뺀 나머지(문자열 값만)
  private static func appData(_ info: [AnyHashable: Any]) -> [String: String] {
    var out: [String: String] = [:]
    for (k, v) in info {
      guard let key = k as? String, key != "aps" else { continue }
      out[key] = "\(v)"
    }
    return out
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

/// GPS 한 번 읽기. 동네 이름만 필요해서 정확도는 1km(kCLLocationAccuracyKilometer)면 충분 → 빠르고 배터리도 적게 쓴다.
/// 오류 코드: PERMISSION_DENIED · LOCATION_OFF · TIMEOUT (Android 와 같음)
final class SbadaLocator: NSObject, CLLocationManagerDelegate {
  private let manager = CLLocationManager()
  private var pending: FlutterResult?
  private var timeout: DispatchWorkItem?

  override init() {
    super.init()
    manager.delegate = self
    manager.desiredAccuracy = kCLLocationAccuracyKilometer
  }

  func current(_ result: @escaping FlutterResult) {
    pending?(FlutterError(code: "CANCELLED", message: "다른 위치 요청이 들어왔어요", details: nil))
    pending = result
    guard CLLocationManager.locationServicesEnabled() else {
      finish(nil, FlutterError(code: "LOCATION_OFF", message: "휴대폰의 위치 서비스가 꺼져 있어요", details: nil)); return
    }
    let work = DispatchWorkItem { [weak self] in
      self?.finish(nil, FlutterError(code: "TIMEOUT", message: "위치를 찾지 못했어요", details: nil))
    }
    timeout = work
    DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: work)
    switch manager.authorizationStatus {
    case .notDetermined: manager.requestWhenInUseAuthorization() // 답을 받으면 아래 didChangeAuthorization 에서 이어감
    case .denied, .restricted:
      finish(nil, FlutterError(code: "PERMISSION_DENIED", message: "위치 권한이 없어요", details: nil))
    default: manager.requestLocation()
    }
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    guard pending != nil else { return }
    switch manager.authorizationStatus {
    case .notDetermined: return
    case .denied, .restricted:
      finish(nil, FlutterError(code: "PERMISSION_DENIED", message: "위치 권한이 없어요", details: nil))
    default: manager.requestLocation()
    }
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let loc = locations.last else { return }
    finish(["lat": loc.coordinate.latitude, "lng": loc.coordinate.longitude], nil)
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    finish(nil, FlutterError(code: "TIMEOUT", message: "위치를 찾지 못했어요", details: error.localizedDescription))
  }

  private func finish(_ value: [String: Double]?, _ error: FlutterError?) {
    timeout?.cancel()
    timeout = nil
    guard let result = pending else { return }
    pending = nil
    if let value = value {
      result(value)
    } else {
      result(error)
    }
  }
}
