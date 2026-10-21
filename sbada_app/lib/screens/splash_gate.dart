import 'dart:convert';

import 'package:flutter/material.dart';

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

class _SplashGateState extends State<SplashGate> {
  Object? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _route());
  }

  Future<void> _route() async {
    final s = widget.services;
    setState(() => _error = null);
    final onboardingDone = await s.session.store.read(Session.onboardingDoneKey) == 'true';
    if (!mounted) return;
    if (!s.session.isLoggedIn) {
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
        if (mounted) _replace(LoginScreen(services: s));
        return;
      }
      if (mounted) setState(() => _error = e);
      return;
    }
    if (mounted) await AppFlow.afterLogin(context, s);
  }

  void _replace(Widget page) {
    Navigator.of(context).pushReplacement(MaterialPageRoute<void>(builder: (_) => page));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Sb.base,
      body: SafeArea(
        child: Center(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Text('🌊', style: TextStyle(fontSize: 72)),
            const SizedBox(height: 12),
            Text('스바다',
                style: TextStyle(fontSize: 40, fontWeight: FontWeight.w900, color: Sb.mintText, letterSpacing: -1)),
            const SizedBox(height: 6),
            Text('쓰던 물건, AI가 값 매겨 바꾸다',
                style: TextStyle(fontSize: 16, color: Sb.text2)),
            const SizedBox(height: 40),
            if (_error == null)
              CircularProgressIndicator(color: Sb.mintText)
            else
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Column(children: [
                  Text(messageOf(_error!), textAlign: TextAlign.center, style: TextStyle(color: Sb.navy)),
                  const SizedBox(height: 16),
                  FilledButton(
                    style: FilledButton.styleFrom(backgroundColor: Sb.blue, foregroundColor: Sb.onAccent),
                    onPressed: _route,
                    child: const Text('다시 연결하기'),
                  ),
                ]),
              ),
          ]),
        ),
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
