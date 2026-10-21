import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// 내 정보(계정정보 목록 맨 아래): 닉네임 · 아이디 변경 + 비밀번호 변경(기존 비밀번호 확인).
///  - PUT /api/me/profile {username, nickname} — 가입 때와 같은 규칙, 남이 쓰는 아이디면 409
///  - PUT /api/me/password {currentPassword, newPassword} — 기존 비밀번호가 맞아야 바뀐다
/// 아이디를 바꿔도 지금 로그인은 그대로 유지된다(토큰은 사용자 번호 기준).
class MyInfoScreen extends StatefulWidget {
  const MyInfoScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<MyInfoScreen> createState() => _MyInfoScreenState();
}

class _MyInfoScreenState extends State<MyInfoScreen> {
  static final _usernameRule = RegExp(r'^[a-z0-9_]{4,20}$');

  late final _nickname = TextEditingController(text: widget.services.session.user?.nickname ?? '');
  late final _username = TextEditingController(text: widget.services.session.user?.username ?? '');
  final _current = TextEditingController();
  final _next = TextEditingController();
  final _confirm = TextEditingController();

  String? _nicknameError, _usernameError, _currentError, _nextError, _confirmError;
  bool _savingProfile = false, _savingPassword = false;

  @override
  void dispose() {
    for (final c in [_nickname, _username, _current, _next, _confirm]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _saveProfile() async {
    final nickname = _nickname.text.trim(), username = _username.text.trim();
    setState(() {
      _nicknameError = nickname.length < 2 || nickname.length > 12 ? '닉네임은 2~12자예요.' : null;
      _usernameError = _usernameRule.hasMatch(username) ? null : '아이디는 영문 소문자 · 숫자 · 밑줄 4~20자예요.';
    });
    if (_nicknameError != null || _usernameError != null) return;
    setState(() => _savingProfile = true);
    try {
      await widget.services.auth.changeProfile(username: username, nickname: nickname);
      if (mounted) showSnack(context, '내 정보를 바꿨어요.');
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        if (e.code == 'USERNAME_EXISTS') {
          _usernameError = e.message;
        } else {
          _usernameError = e.errors['username'];
          _nicknameError = e.errors['nickname'];
        }
      });
      if (_usernameError == null && _nicknameError == null) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _savingProfile = false);
    }
  }

  Future<void> _savePassword() async {
    setState(() {
      _currentError = _current.text.isEmpty ? '기존 비밀번호를 입력해 주세요.' : null;
      _nextError = _next.text.length < 8 || _next.text.length > 64 ? '새 비밀번호는 8~64자예요.' : null;
      _confirmError = _confirm.text != _next.text ? '새 비밀번호가 서로 달라요.' : null;
    });
    if (_currentError != null || _nextError != null || _confirmError != null) return;
    setState(() => _savingPassword = true);
    try {
      await widget.services.auth.changePassword(current: _current.text, next: _next.text);
      if (!mounted) return;
      _current.clear();
      _next.clear();
      _confirm.clear();
      showSnack(context, '비밀번호를 바꿨어요. 다음 로그인부터 새 비밀번호를 써 주세요.');
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        if (e.code == 'WRONG_PASSWORD') {
          _currentError = e.message;
        } else {
          _nextError = e.message;
        }
      });
    } finally {
      if (mounted) setState(() => _savingPassword = false);
    }
  }

  Widget _label(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Text(text, style: TextStyle(fontSize: 13, color: Sb.text2)),
      );

  Widget _section(String title, List<Widget> children) => Container(
        margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
        decoration: BoxDecoration(color: Sb.isDark ? Sb.card : Sb.bg, borderRadius: BorderRadius.circular(16)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(title, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Sb.navy)),
          const SizedBox(height: 14),
          ...children,
        ]),
      );

  /// 카드(회색) 위 입력칸은 한 단계 다른 색으로
  InputDecoration _input(String? hint, String? error) =>
      sbInput(null, hint: hint, errorText: error).copyWith(fillColor: Sb.isDark ? Sb.bg : Sb.base);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('내 정보', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
      body: GestureDetector(
        onTap: () => FocusScope.of(context).unfocus(),
        child: ListView(padding: const EdgeInsets.only(top: 4, bottom: 32), children: [
          _section('프로필', [
            _label('닉네임'),
            TextField(controller: _nickname, maxLength: 12, decoration: _input('2~12자', _nicknameError)),
            _label('아이디'),
            TextField(
              controller: _username,
              maxLength: 20,
              autocorrect: false,
              decoration: _input('영문 소문자 · 숫자 · 밑줄 4~20자', _usernameError),
            ),
            const SizedBox(height: 4),
            FilledButton(
              onPressed: _savingProfile ? null : _saveProfile,
              child: _savingProfile
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                  : const Text('저장하기'),
            ),
          ]),
          _section('비밀번호 변경', [
            _label('기존 비밀번호'),
            TextField(controller: _current, obscureText: true, decoration: _input('지금 쓰는 비밀번호', _currentError)),
            const SizedBox(height: 12),
            _label('새 비밀번호'),
            TextField(controller: _next, obscureText: true, decoration: _input('8~64자', _nextError)),
            const SizedBox(height: 12),
            _label('새 비밀번호 확인'),
            TextField(
              controller: _confirm,
              obscureText: true,
              onSubmitted: (_) => _savePassword(),
              decoration: _input('한 번 더 입력', _confirmError),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _savingPassword ? null : _savePassword,
              child: _savingPassword
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                  : const Text('비밀번호 바꾸기'),
            ),
            const SizedBox(height: 8),
            Text('카카오로 가입한 계정은 비밀번호가 없어 여기서 바꿀 수 없어요.', style: sbCaption),
          ]),
        ]),
      ),
    );
  }
}
