import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/user.dart';
import '../network/api_exception.dart';
import '../network/session.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'home_shell.dart';
import 'login_screen.dart';
import 'onboarding_screen.dart';

/// 첫 화면: 어디로 보낼지 정한다.
///   처음 설치 → 온보딩(바꾸고 싶은 물건·관심 키워드·가격대) → 로그인/회원가입
///   로그인 기록 있음 → 서버에서 내 정보 확인 → 홈
class SplashGate extends StatefulWidget {
  const SplashGate({super.key, required this.services});

  final AppServices services;

  @override
  State<SplashGate> createState() => _SplashGateState();
}

class _SplashGateState extends State<SplashGate> with SingleTickerProviderStateMixin {
  /// 실행 화면을 보여 주는 최소 시간. 그 사이에 로그인 확인 · 내 정보 불러오기를 같이 한다.
  static const minShow = Duration(milliseconds: 2500);

  Object? _error;
  late final Future<void> _shown = Future<void>.delayed(minShow);
  bool _slow = false; // 2.5초가 지나도 아직 서버를 기다리는 중이면 작은 로딩 표시
  late final AnimationController _intro =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 900))..forward();

  @override
  void initState() {
    super.initState();
    _shown.then((_) {
      if (mounted) setState(() => _slow = true);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => _route());
  }

  @override
  void dispose() {
    _intro.dispose();
    super.dispose();
  }

  Future<void> _route() async {
    final s = widget.services;
    setState(() => _error = null);
    final onboardingDone = await s.session.store.read(Session.onboardingDoneKey) == 'true';
    if (!mounted) return;
    if (!s.session.isLoggedIn) {
      await _shown;
      if (!mounted) return;
      _replace(onboardingDone
          ? LoginScreen(services: s)
          : OnboardingScreen(services: s, mode: OnboardingMode.firstRun));
      return;
    }
    try {
      await s.auth.me();
    } on ApiException catch (e) {
      if (e.isUnauthorized) {
        await s.logout();
        await _shown;
        if (mounted) _replace(LoginScreen(services: s));
        return;
      }
      await _shown;
      if (mounted) setState(() => _error = e);
      return;
    }
    await _shown;
    if (mounted) await AppFlow.afterLogin(context, s);
  }

  void _replace(Widget page) {
    Navigator.of(context).pushReplacement(PageRouteBuilder<void>(
      pageBuilder: (_, _, _) => page,
      transitionDuration: const Duration(milliseconds: 350),
      transitionsBuilder: (_, a, _, child) => FadeTransition(opacity: a, child: child),
    ));
  }

  /// 실행 화면: 앱 아이콘 배경색 위 정가운데에 아이콘 그림, 그 아래 "쓰던 물건을 바꾸다."
  /// 다크/화이트 모드와 상관없이 같은 색(아이콘과 이어져 보이게). 글자는 민트 위 규칙대로 짙은 색.
  @override
  Widget build(BuildContext context) {
    const logo = SwapLogo.nativeMatchedSize;
    // 아이콘 그림(splash_left/right.png)은 정사각형이고 그림은 가운데 약 절반 높이 → 문구는 그림 바로 아래에 둔다
    const textTop = logo * 0.27 + 18;
    final textIn = CurvedAnimation(parent: _intro, curve: const Interval(0.35, 1, curve: Curves.easeOut));
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.dark.copyWith(statusBarColor: Colors.transparent),
      child: Scaffold(
        backgroundColor: Sb.splashBg,
        body: Stack(children: [
          // 곰 · 손 그림은 앱을 누르자마자 Android 시작 화면(splash_icon)에 같은 크기 · 자리로 이미 떠 있다
          // → 여기서는 서서히 나타나지 않고 바로 그린 뒤 위아래로 움직이기만 한다
          const Center(child: SwapLogo(size: logo)),
          Center(
            child: Padding(
              padding: EdgeInsets.only(top: textTop * 2 + 26),
              child: FadeTransition(
                opacity: textIn,
                child: const Text('쓰던 물건을 바꾸다.',
                    style: TextStyle(
                        fontSize: 20, fontWeight: FontWeight.w800, color: Sb.onAccent, letterSpacing: -0.4, height: 1.3)),
              ),
            ),
          ),
          Positioned(
            left: 32,
            right: 32,
            bottom: 48,
            child: SafeArea(
              top: false,
              child: _error != null
                  ? Column(mainAxisSize: MainAxisSize.min, children: [
                      Text(messageOf(_error!),
                          textAlign: TextAlign.center, style: const TextStyle(color: Sb.onAccent, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 12),
                      FilledButton(
                        style: FilledButton.styleFrom(backgroundColor: Sb.onAccent, foregroundColor: Colors.white),
                        onPressed: _route,
                        child: const Text('다시 연결하기'),
                      ),
                    ])
                  : _slow
                      ? const Center(
                          child: SizedBox(
                              width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent)))
                      : const SizedBox.shrink(),
            ),
          ),
        ]),
      ),
    );
  }
}

/// 로그인 뒤 공통 흐름
class AppFlow {
  /// 로그인 전에 고른 온보딩 선택이 있으면 서버에 저장하고, 없으면 온보딩을 연다.
  static Future<void> afterLogin(BuildContext context, AppServices s) async {
    final user = s.session.user;
    if (user != null && !user.onboarded) {
      final raw = await s.session.store.read(Session.pendingPrefsKey);
      if (raw != null) {
        try {
          await s.auth.savePreferences(OnboardingChoice.fromJson(jsonDecode(raw) as Map<String, dynamic>));
          await s.session.store.remove(Session.pendingPrefsKey);
        } catch (_) {
          // 실패하면 아래에서 온보딩 화면을 다시 연다
        }
      }
    }
    // 로그인 전 온보딩에서 고른 사는 동네가 있으면 저장(가입할 때 적은 동네보다 우선)
    final region = await s.session.store.read(Session.pendingRegionKey);
    if (region != null && region.trim().isNotEmpty && s.session.user != null) {
      try {
        await s.auth.changeRegion(region.trim());
        await s.session.store.remove(Session.pendingRegionKey);
      } catch (_) {
        // 실패해도 홈으로 간다(계정정보 > 내 동네 설정에서 다시 바꿀 수 있음)
      }
    }
    if (!context.mounted) return;
    final onboarded = s.session.user?.onboarded ?? false;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute<void>(
        builder: (_) => onboarded
            ? HomeShell(services: s)
            : OnboardingScreen(services: s, mode: OnboardingMode.afterLogin),
      ),
      (route) => false,
    );
  }

  static void goHome(BuildContext context, AppServices s) {
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute<void>(builder: (_) => HomeShell(services: s)),
      (route) => false,
    );
  }
}

/// 실행 화면 아이콘 그림: 왼쪽(곰 + 왼손)과 오른쪽(컵 + 오른손)을 두 장으로 나눠
/// 서로 반대로 천천히 위아래로 오가게 한다(물건을 주고받는 느낌).
///  - 두 장은 같은 크기 · 같은 자리의 투명 PNG 라 겹치면 원래 아이콘 그림과 똑같다
///  - 사인 곡선으로 움직여 끝에서 부드럽게 멈췄다 돌아오고, 살짝 기울어 손으로 드는 느낌을 준다
///  - 휴대폰 '애니메이션 줄이기'가 켜져 있으면 움직이지 않는다
class SwapLogo extends StatefulWidget {
  const SwapLogo({super.key, required this.size});

  final double size;

  /// Android 시작 화면(res/drawable-xxxhdpi/splash_icon.png: 그림 가로 170dp)과 같은 크기가 되는 값.
  /// splash_left/right.png 에서 그림 가로는 정사각형 한 변의 0.9333 → 170 / 0.9333
  static const nativeMatchedSize = 170 / 0.9333;

  /// 왼쪽(곰 + 왼손) · 오른쪽(컵 + 오른손) 그림
  static const assets = ['assets/splash_left.png', 'assets/splash_right.png'];

  /// 앱 시작(main) 때 runApp 전에 두 그림을 미리 풀어 둔다. 그동안은 Android 시작 화면(같은 그림)이 그대로 보이고,
  /// 첫 화면부터 그림이 바로 그려져 넘어가는 순간 그림이 잠깐 사라지는 깜빡임이 없다. 실패해도 그냥 넘어간다.
  static Future<void> precache() => Future.wait([
        for (final a in assets) _resolve(AssetImage(a)),
      ]).timeout(const Duration(seconds: 6), onTimeout: () => const []);

  static Future<void> _resolve(ImageProvider provider) {
    final done = Completer<void>();
    final stream = provider.resolve(ImageConfiguration.empty);
    late final ImageStreamListener listener;
    listener = ImageStreamListener((_, _) {
      if (!done.isCompleted) done.complete();
      stream.removeListener(listener);
    }, onError: (_, _) {
      if (!done.isCompleted) done.complete();
      stream.removeListener(listener);
    });
    stream.addListener(listener);
    return done.future;
  }

  /// 한 번 올라갔다 내려오는 시간
  static const period = Duration(milliseconds: 2400);

  @override
  State<SwapLogo> createState() => _SwapLogoState();
}

class _SwapLogoState extends State<SwapLogo> with SingleTickerProviderStateMixin {
  late final AnimationController _bob = AnimationController(vsync: this, duration: SwapLogo.period)..repeat();

  @override
  void dispose() {
    _bob.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    final still = MediaQuery.of(context).disableAnimations;
    final amp = size * 0.028; // 움직이는 높이(300 크기에서 약 8)
    Widget layer(String asset, double y, double turn, Alignment pivot) => Transform.translate(
          offset: Offset(0, y),
          child: Transform.rotate(
            angle: turn,
            alignment: pivot,
            child: Image.asset(asset, width: size, height: size, filterQuality: FilterQuality.medium),
          ),
        );
    return Semantics(
      label: '스바다',
      image: true,
      child: SizedBox(
        width: size,
        height: size,
        child: AnimatedBuilder(
          animation: _bob,
          builder: (_, _) {
            // 0 → 1 을 한 바퀴(2π)로: 왼쪽이 올라갈 때 오른쪽은 내려간다
            final s = still ? 0.0 : math.sin(_bob.value * 2 * math.pi);
            return Stack(children: [
              layer(SwapLogo.assets[0], -amp * s, -0.025 * s, const Alignment(-0.4, 0.4)),
              layer(SwapLogo.assets[1], amp * s, 0.025 * s, const Alignment(0.4, 0.4)),
            ]);
          },
        ),
      ),
    );
  }
}
