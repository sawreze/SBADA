import 'package:flutter/material.dart';

import '../config.dart';
import '../models/category.dart';
import '../models/user.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/settings_group.dart';
import 'profile_screen.dart' show ThemeToggle;

/// 앱 설정: 화면 모드 · 알림 권한 · 내 취향(알고리즘이 학습한 선호도) · 연결 상태 · 앱 정보.
/// 예전 계정정보 아래쪽에 있던 '학습한 선호도 · 관심 키워드 · 가격대 · 연결 상태'를 이곳으로 옮겼다(시연 때 설명용).
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late Future<MeProfile> _me;
  Map<String, dynamic>? _health;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _me = widget.services.auth.me();
    widget.services.auth.health().then((h) {
      if (mounted) setState(() => _health = h);
    }).catchError((Object _) {
      if (mounted) setState(() => _health = null);
    });
  }

  Future<void> _askNotification() async {
    final ok = await widget.services.notifier.requestPermission();
    if (mounted) showSnack(context, ok ? '알림이 켜져 있어요.' : '알림 권한이 없어요. 휴대폰 설정 > 앱 > 스바다 > 알림에서 켤 수 있어요.');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('앱 설정', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
      body: RefreshIndicator(
        onRefresh: () async {
          setState(_load);
          await _me;
        },
        child: ListView(padding: const EdgeInsets.only(top: 4, bottom: 32), children: [
          SettingsGroup(title: '화면', children: [
            SettingsRow(
              icon: Icons.dark_mode_outlined,
              label: '다크 / 화이트 모드',
              subtitle: '기기에 저장돼요',
              showChevron: false,
              trailing: const ThemeToggle(),
            ),
          ]),
          SettingsGroup(title: '알림', children: [
            SettingsRow(
              icon: Icons.notifications_none_rounded,
              label: '알림 권한 확인',
              subtitle: '교환 요청 · 채팅이 오면 알림 표시줄에 떠요',
              onTap: _askNotification,
            ),
          ]),
          FutureBuilder<MeProfile>(
            future: _me,
            builder: (context, snap) {
              final me = snap.data;
              if (me == null) return const SizedBox.shrink();
              return SettingsGroup(title: '내 취향 · 알고리즘이 학습한 선호도', children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 2, 16, 8),
                  child: Text(
                    '직접 고른 종류는 1.0에서 시작하고, 물건을 보거나(+3%) 채팅하거나(+10%) 교환을 요청(+20%)·성사(+30%)하면 '
                    'w ← w + 비율×(1−w) 로 조금씩 올라가요.',
                    style: sbCaption,
                  ),
                ),
                if (me.preferences.isEmpty)
                  Padding(padding: const EdgeInsets.all(16), child: Text('아직 선호도가 없어요.', style: sbBody)),
                for (final p in me.preferences) _preferenceRow(p),
                if (me.keywords.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
                    child: Wrap(spacing: 6, runSpacing: 6, children: [
                      for (final k in me.keywords) Pill('#$k', color: Sb.blueSoft, textColor: Sb.mintText),
                    ]),
                  ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                  child: Text('주로 교환할 가격대: ${_band(me)}', style: TextStyle(fontSize: 13, color: Sb.text2)),
                ),
              ]);
            },
          ),
          SettingsGroup(title: '연결 상태', children: [
            _status('서버', AppConfig.apiBaseUrl, _health != null),
            ValueListenableBuilder<bool>(
              valueListenable: widget.services.realtime.connected,
              builder: (context, on, child) => _status('실시간(WebSocket)', AppConfig.wsUrl, on),
            ),
            _status('AI 가격 분석', _aiLabel(), _health != null),
          ]),
          SettingsGroup(title: '앱 정보', children: [
            SettingsRow(
              icon: Icons.info_outline_rounded,
              label: '스바다',
              subtitle: '버전 1.0.0 · 사진 한 장으로 AI가 값을 매기는 물물교환',
              showChevron: false,
            ),
          ]),
        ]),
      ),
    );
  }

  Widget _preferenceRow(PreferenceWeight p) {
    final info = CategoryInfo.of(p.category);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Row(children: [
        SizedBox(width: 28, child: Text(info.emoji, style: const TextStyle(fontSize: 18))),
        SizedBox(width: 92, child: Text(p.label, style: TextStyle(fontWeight: FontWeight.w600, color: Sb.navy))),
        Expanded(
          child: ClipRRect(
            borderRadius: BorderRadius.circular(99),
            child: LinearProgressIndicator(
              value: p.weight.clamp(0, 1).toDouble(),
              minHeight: 8,
              backgroundColor: Sb.chip,
              color: p.source == 'ONBOARDING' ? Sb.blue : const Color(0xFF7FB2FF),
            ),
          ),
        ),
        SizedBox(
          width: 72,
          child: Text('${(p.weight * 100).round()}% ${p.source == 'ONBOARDING' ? '선택' : '학습'}',
              textAlign: TextAlign.right, style: sbCaption),
        ),
      ]),
    );
  }

  String _band(MeProfile me) {
    if (me.priceMin == null && me.priceMax == null) return '상관없어요';
    final min = me.priceMin == null ? '' : formatWon(me.priceMin!);
    final max = me.priceMax == null ? '' : formatWon(me.priceMax!);
    return '$min ~ $max';
  }

  String _aiLabel() {
    final ai = _health?['ai'];
    if (ai is! Map) return '확인 중…';
    return switch (ai['active']) {
      'google' => 'Gemma 4 · Google AI Studio (${ai['googleModel']})',
      'ollama' => 'Gemma · 로컬 Ollama (${ai['ollamaModel']})',
      _ => '기준가 추정 모드 (AI 키 미설정)',
    };
  }

  Widget _status(String title, String detail, bool ok) {
    return SettingsRow(
      icon: ok ? Icons.check_circle_outline_rounded : Icons.error_outline_rounded,
      label: title,
      subtitle: detail,
      showChevron: false,
      trailing: Icon(Icons.circle, size: 10, color: ok ? Sb.green : Sb.red),
    );
  }
}
