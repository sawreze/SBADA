import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../services/kakao_login.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/expand_route.dart';
import '../widgets/liquid_button.dart';
import 'login_screen.dart';
import 'splash_gate.dart';

/// 시작 화면(디자인 캔버스 'Start', 2026-11-24):
///   sbada 로고 글자 → “쓰던 물건을 바꾸다” → 교환 일러스트 → Google · Apple · 카카오 · 네이버로 시작 → 먼저 둘러보기
/// 실제로 되는 소셜 로그인은 카카오. Google · Apple · 네이버는 서버 · SDK 를 붙이기 전까지 안내만 띄운다.
class StartScreen extends StatefulWidget {
  const StartScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<StartScreen> createState() => _StartScreenState();
}

class _StartScreenState extends State<StartScreen> {
  bool _kakaoLoading = false;

  Future<void> _kakao() async {
    if (_kakaoLoading) return;
    setState(() => _kakaoLoading = true);
    try {
      final token = await KakaoLogin.obtainAccessToken();
      if (token == null || !mounted) return; // 사용자가 취소
      await widget.services.session.setKeepLogin(true);
      await widget.services.auth.kakaoLogin(token, remember: true);
      if (!mounted) return;
      await AppFlow.afterLogin(context, widget.services);
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } on KakaoLoginException catch (e) {
      if (mounted) showSnack(context, e.message);
    } finally {
      if (mounted) setState(() => _kakaoLoading = false);
    }
  }

  void _soon(String name) => showSnack(context, '$name 로그인은 준비 중이에요. 카카오나 이메일로 시작해 주세요.');

  void _idLogin({String? notice}) => Navigator.of(context)
      .push(appRoute<void>(builder: (_) => LoginScreen(services: widget.services, notice: notice)));

  Widget _logo(String name) => ClipOval(
        child: Image.asset('assets/login/login_$name.png', width: 28, height: 28, fit: BoxFit.cover),
      );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFFBFAF6),
      body: Stack(children: [
        // 유리 버튼 뒤에 비칠 옅은 색 번짐
        const Positioned(left: -80, bottom: 90, child: _Glow(color: Color(0x8C93E1B7), size: 280)),
        const Positioned(right: -90, bottom: -40, child: _Glow(color: Color(0x61FFC46B), size: 300)),
        SafeArea(
          child: LayoutBuilder(builder: (context, box) {
            return SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 36, 24, 20),
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: box.maxHeight - 56),
                child: Column(children: [
                  Text.rich(
                    key: const ValueKey('start-wordmark'),
                    const TextSpan(children: [
                      TextSpan(text: 'sba', style: TextStyle(color: Color(0xFF2B2B2B))),
                      TextSpan(text: 'da', style: TextStyle(color: Color(0xFF4F9A57))),
                    ]),
                    style: const TextStyle(fontSize: 54, fontWeight: FontWeight.w800, letterSpacing: -1.5, height: 1),
                  ),
                  const SizedBox(height: 14),
                  const Text('“쓰던 물건을 바꾸다”',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                        color: Color(0xFF4A5058),
                        fontFamilyFallback: ['AppleMyungjo', 'Noto Serif KR', 'serif'],
                      )),
                  const SizedBox(height: 20),
                  TweenAnimationBuilder<double>(
                    tween: Tween(begin: 0, end: 1),
                    duration: const Duration(milliseconds: 700),
                    curve: Curves.easeOutCubic,
                    builder: (context, t, child) =>
                        Opacity(opacity: t, child: Transform.translate(offset: Offset(0, 16 * (1 - t)), child: child)),
                    child: Image.asset('assets/start/start_illust.png',
                        height: 270, fit: BoxFit.contain, semanticLabel: '두 사람이 물건을 바꾸는 그림'),
                  ),
                  const SizedBox(height: 24),
                  LiquidButton(
                      primary: true,
                      label: 'Google 계정으로 시작',
                      leading: _logo('google'),
                      onPressed: () => _soon('Google')),
                  const SizedBox(height: 10),
                  LiquidButton(label: 'Apple 계정으로 시작', leading: _logo('apple'), onPressed: () => _soon('Apple')),
                  const SizedBox(height: 10),
                  LiquidButton(
                      key: const ValueKey('start-kakao'),
                      label: '카카오로 시작',
                      leading: _logo('kakao'),
                      busy: _kakaoLoading,
                      onPressed: _kakao),
                  const SizedBox(height: 10),
                  LiquidButton(label: '네이버로 시작', leading: _logo('naver'), onPressed: () => _soon('네이버')),
                  const SizedBox(height: 12),
                  Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                    TextButton(
                      onPressed: () => _idLogin(notice: '둘러보기: 시연 계정(minji)으로 로그인해 보세요.'),
                      child: Text('먼저 둘러보기 →', style: TextStyle(color: Sb.p.text2, fontWeight: FontWeight.w600)),
                    ),
                    Text('·', style: TextStyle(color: Sb.p.text3)),
                    TextButton(
                      key: const ValueKey('start-id-login'),
                      onPressed: _idLogin,
                      child: Text('이메일로 로그인', style: TextStyle(color: Sb.p.text2, fontWeight: FontWeight.w600)),
                    ),
                  ]),
                ]),
              ),
            );
          }),
        ),
      ]),
    );
  }
}

class _Glow extends StatelessWidget {
  const _Glow({required this.color, required this.size});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) => IgnorePointer(
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            gradient: RadialGradient(colors: [color, color.withValues(alpha: 0)]),
          ),
        ),
      );
}
