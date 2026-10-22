import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../network/session.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/gps_dong_button.dart';
import 'splash_gate.dart';

class SignupScreen extends StatefulWidget {
  const SignupScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<SignupScreen> createState() => _SignupScreenState();
}

class _SignupScreenState extends State<SignupScreen> {
  final _formKey = GlobalKey<FormState>();
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _nickname = TextEditingController();
  final _region = TextEditingController(text: '마포구');
  bool _loading = false;
  ApiException? _error;

  @override
  void initState() {
    super.initState();
    // 처음 쓸 때 온보딩 ④에서 고른 사는 동네가 있으면 '동네' 칸에 미리 채운다
    widget.services.session.store.read(Session.pendingRegionKey).then((r) {
      if (mounted && r != null && r.trim().isNotEmpty) setState(() => _region.text = r.trim());
    });
  }

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    _nickname.dispose();
    _region.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_loading || !_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.services.auth.signup(
        username: _username.text,
        password: _password.text,
        nickname: _nickname.text,
        region: _region.text,
      );
      // 가입할 때 보낸 동네(미리 채운 값을 사용자가 고쳤을 수도 있음)가 최종 → 온보딩에서 남긴 값은 지운다
      await widget.services.session.store.remove(Session.pendingRegionKey);
      if (!mounted) return;
      await AppFlow.afterLogin(context, widget.services);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final e = _error;
    final keyboard = MediaQuery.viewInsetsOf(context).bottom;
    return Scaffold(
      // 로그인 화면과 같은 키보드 출렁임 수정(화면 높이 고정 + 키보드 높이만큼 아래 여백)
      resizeToAvoidBottomInset: false,
      appBar: AppBar(title: const Text('회원가입')),
      body: SafeArea(
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            padding: EdgeInsets.fromLTRB(24, 24, 24, 24 + keyboard),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('간단하게\n가입하고 시작해요', style: sbTitle),
            const SizedBox(height: 24),
            TextFormField(
              controller: _username,
              autocorrect: false,
              decoration: sbInput('아이디', hint: '영문 소문자·숫자·밑줄 4~20자', errorText: e?.fieldError('username')),
              validator: (v) => RegExp(r'^[a-z0-9_]{4,20}$').hasMatch(v?.trim() ?? '')
                  ? null
                  : '영문 소문자·숫자·밑줄 4~20자로 입력해 주세요.',
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _password,
              obscureText: true,
              decoration: sbInput('비밀번호', hint: '8자 이상', errorText: e?.fieldError('password')),
              validator: (v) => (v?.length ?? 0) >= 8 ? null : '비밀번호는 8자 이상입니다.',
            ),
            const SizedBox(height: 12),
            TextFormField(
              controller: _nickname,
              decoration: sbInput('닉네임', hint: '2~12자', errorText: e?.fieldError('nickname')),
              validator: (v) {
                final t = v?.trim() ?? '';
                return t.length >= 2 && t.length <= 12 ? null : '닉네임은 2~12자입니다.';
              },
            ),
            const SizedBox(height: 12),
            TextFormField(controller: _region, decoration: sbInput('동네', hint: '예) 마포구 망원동')),
            const SizedBox(height: 8),
            // GPS 로 찾으면 '동네' 칸에 채워진다(고칠 수 있음). 온보딩 ④ · 내 동네 설정과 같은 기능의 작은 버튼
            GpsDongButton(
              services: widget.services,
              compact: true,
              onFound: (d) => setState(() => _region.text = d.region),
            ),
            if (e != null && e.errors.isEmpty) ...[
              const SizedBox(height: 12),
              Text(e.message, style: TextStyle(color: Sb.red)),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: _loading ? null : _submit,
              child: _loading
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                  : const Text('가입하고 시작하기'),
            ),
          ])),
        ),
      ),
    );
  }
}
