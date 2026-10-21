import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../services/kakao_login.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'signup_screen.dart';
import 'splash_gate.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.services, this.notice});

  final AppServices services;
  final String? notice;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _loading = false;
  bool _kakaoLoading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.notice != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) showSnack(context, widget.notice!);
      });
    }
  }

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (_loading) return;
    if (_username.text.trim().isEmpty || _password.text.isEmpty) {
      setState(() => _error = '아이디와 비밀번호를 입력해 주세요.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.services.auth.login(_username.text, _password.text);
      if (!mounted) return;
      await AppFlow.afterLogin(context, widget.services);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  /// 카카오로 시작하기: 카카오 SDK 로그인 → 카카오 토큰을 서버에 보내 스바다 JWT 받기 → 로그인과 같은 다음 화면
  Future<void> _kakao() async {
    if (_loading || _kakaoLoading) return;
    setState(() {
      _kakaoLoading = true;
      _error = null;
    });
    try {
      final kakaoToken = await KakaoLogin.obtainAccessToken();
      if (kakaoToken == null || !mounted) return; // 사용자가 취소
      await widget.services.auth.kakaoLogin(kakaoToken);
      if (!mounted) return;
      await AppFlow.afterLogin(context, widget.services);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } on KakaoLoginException catch (e) {
      if (mounted) showSnack(context, e.message);
    } finally {
      if (mounted) setState(() => _kakaoLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    // 키보드 버그 수정: 키보드가 올라올 때 Scaffold 가 화면 높이를 줄이면서(resize) 동시에
    // 목록이 입력칸을 보이게 스크롤해 화면이 위아래로 출렁였다.
    // → 화면 높이는 그대로 두고(resizeToAvoidBottomInset: false), 키보드 높이만큼 아래 여백만 늘린다.
    final keyboard = MediaQuery.viewInsetsOf(context).bottom;
    return Scaffold(
      resizeToAvoidBottomInset: false,
      body: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onTap: () => FocusScope.of(context).unfocus(),
        child: SafeArea(
          // 디자인 시안(로그인): 민트 로고 · 두 줄 제목 · 설명 · 라벨 위 입력칸 2개 · 로그인 버튼 · 맨 아래 회원가입 링크
          child: LayoutBuilder(
            builder: (context, box) => SingleChildScrollView(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: EdgeInsets.fromLTRB(24, 64, 24, 24 + keyboard),
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: box.maxHeight - 88),
                child: IntrinsicHeight(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Align(
                      alignment: Alignment.centerLeft,
                      child: Container(
                        width: 64,
                        height: 64,
                        decoration: BoxDecoration(color: Sb.blue, borderRadius: BorderRadius.circular(20)),
                        child: const Icon(Icons.swap_horiz_rounded, size: 36, color: Sb.onAccent),
                      ),
                    ),
                    const SizedBox(height: 24),
                    Text('안 쓰는 물건,\n비슷한 값어치로 바꿔요',
                        style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, color: Sb.navy, height: 1.35)),
                    const SizedBox(height: 8),
                    Text('사진만 찍으면 AI가 가격을 매기고\n같은 가격대 이웃을 찾아 줘요.',
                        style: TextStyle(fontSize: 15, color: Sb.text2, height: 1.5)),
                    const SizedBox(height: 36),
                    _label('아이디'),
                    TextField(
                      controller: _username,
                      textInputAction: TextInputAction.next,
                      autocorrect: false,
                      scrollPadding: const EdgeInsets.only(bottom: 220),
                      decoration: sbInput(null, hint: '아이디를 입력하세요'),
                    ),
                    const SizedBox(height: 12),
                    _label('비밀번호'),
                    TextField(
                      controller: _password,
                      obscureText: true,
                      textInputAction: TextInputAction.done,
                      scrollPadding: const EdgeInsets.only(bottom: 160),
                      onSubmitted: (_) => _login(),
                      decoration: sbInput(null, hint: '비밀번호를 입력하세요', errorText: _error),
                    ),
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: _loading ? null : _login,
                      child: _loading
                          ? const SizedBox(
                              width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                          : const Text('로그인'),
                    ),
                    const SizedBox(height: 16),
                    Row(children: [
                      Expanded(child: Divider(color: Sb.text3.withValues(alpha: 0.3))),
                      Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        child: Text('또는', style: TextStyle(fontSize: 13, color: Sb.text3)),
                      ),
                      Expanded(child: Divider(color: Sb.text3.withValues(alpha: 0.3))),
                    ]),
                    const SizedBox(height: 16),
                    KakaoStartButton(loading: _kakaoLoading, onPressed: _loading ? null : _kakao),
                    const Spacer(),
                    const SizedBox(height: 24),
                    Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Text('처음이신가요?', style: TextStyle(fontSize: 15, color: Sb.text3)),
                      TextButton(
                        onPressed: () => Navigator.of(context)
                            .push(MaterialPageRoute<void>(builder: (_) => SignupScreen(services: widget.services))),
                        style: TextButton.styleFrom(minimumSize: const Size(48, 48)),
                        child: const Text('회원가입', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
                      ),
                    ]),
                  ]),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// 입력칸 위 작은 라벨(디자인 시안: 13 · 보조 글자색)
  Widget _label(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Text(text, style: TextStyle(fontSize: 13, color: Sb.text2)),
      );
}

/// 카카오 로그인 버튼(카카오 디자인 가이드: 배경 #FEE500, 글자·심볼 검정 85%, 말풍선 심볼)
class KakaoStartButton extends StatelessWidget {
  const KakaoStartButton({super.key, required this.loading, required this.onPressed});

  final bool loading;
  final VoidCallback? onPressed;

  static const _yellow = Color(0xFFFEE500);
  static const _label = Color(0xD9000000); // 검정 85%

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 52,
      child: FilledButton(
        key: const ValueKey('kakao-login'),
        onPressed: loading ? null : onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: _yellow,
          foregroundColor: _label,
          disabledBackgroundColor: _yellow.withValues(alpha: 0.6),
          disabledForegroundColor: _label,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
        child: loading
            ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: _label))
            : const Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                Icon(Icons.chat_bubble, size: 20, color: _label),
                SizedBox(width: 8),
                Text('카카오로 시작하기', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: _label)),
              ]),
      ),
    );
  }
}
