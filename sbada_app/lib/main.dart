import 'package:flutter/material.dart';

import 'screens/login_screen.dart';
import 'screens/splash_gate.dart';
import 'services/app_services.dart';
import 'services/push_service.dart';
import 'services/theme_controller.dart';
import 'theme/app_theme.dart';

/// 스바다 앱 시작점.
///   flutter run -d emulator-5554 --dart-define=API_BASE_URL=http://10.0.2.2:8097
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await ThemeController.load(); // 저장해 둔 다크/화이트 모드
  await PushService.init(); // FCM(푸시): google-services.json 이 없으면 조용히 꺼짐
  // 실행 화면 그림(곰 · 손)을 미리 불러 두기 — 서비스 준비와 같이 기다린다
  final (services, _) = await (AppServices.create(), SwapLogo.precache()).wait;
  runApp(SbadaApp(services: services));
}

final GlobalKey<NavigatorState> rootNavigator = GlobalKey<NavigatorState>();

class SbadaApp extends StatefulWidget {
  const SbadaApp({super.key, required this.services, this.home});

  final AppServices services;

  /// 테스트에서 첫 화면을 바꿔 끼울 때 쓴다
  final Widget? home;

  @override
  State<SbadaApp> createState() => _SbadaAppState();
}

class _SbadaAppState extends State<SbadaApp> {
  bool _handlingUnauthorized = false;

  @override
  void initState() {
    super.initState();
    Sb.darkMode.addListener(_onThemeChanged);
    // 토큰이 만료되면(401) 로그인 화면으로 돌아간다
    widget.services.api.onUnauthorized = () async {
      if (_handlingUnauthorized) return;
      _handlingUnauthorized = true;
      await widget.services.logout();
      rootNavigator.currentState?.pushAndRemoveUntil(
        MaterialPageRoute<void>(
            builder: (_) => LoginScreen(services: widget.services, notice: '로그인이 만료되었어요. 다시 로그인해 주세요.')),
        (route) => false,
      );
      _handlingUnauthorized = false;
    };
  }

  @override
  void dispose() {
    Sb.darkMode.removeListener(_onThemeChanged);
    super.dispose();
  }

  /// 다크/화이트 모드가 바뀌면 테마를 새로 만들고, 열려 있는 모든 화면을 다시 그린다.
  void _onThemeChanged() {
    setState(() {});
    ThemeController.rebuildAll(context);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      // 색 토큰이 즉시 바뀌므로 테마 애니메이션은 끈다(반쯤 섞인 색으로 깜빡이지 않게)
      themeAnimationDuration: Duration.zero,
      title: '스바다',
      debugShowCheckedModeBanner: false,
      navigatorKey: rootNavigator,
      theme: buildTheme(),
      home: widget.home ?? SplashGate(services: widget.services),
    );
  }
}
