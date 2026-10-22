import 'package:flutter/material.dart';

import '../models/app_notification.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../services/push_service.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/settings_group.dart';

/// 계정정보 > 알림 설정: 찜 · 채팅 · 교환 알림 켜기/끄기(서버 PUT /api/me/notification-settings) + 푸시 상태.
/// 끈 종류는 서버가 푸시(FCM/APNs)를 보내지 않고, 앱이 켜져 있을 때도 배너를 띄우지 않는다(알림함 기록은 남음).
class NotificationSettingsScreen extends StatefulWidget {
  const NotificationSettingsScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<NotificationSettingsScreen> createState() => _NotificationSettingsScreenState();
}

class _NotificationSettingsScreenState extends State<NotificationSettingsScreen> {
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    widget.services.refreshNotificationSettings();
  }

  /// 스위치는 먼저 바꿔 보여 주고(바로 반응), 서버가 실패하면 되돌린다
  Future<void> _change(String key, bool on) async {
    final s = widget.services;
    final before = s.notificationSettings.value;
    s.notificationSettings.value = NotificationSettings(
      like: key == 'like' ? on : before.like,
      chat: key == 'chat' ? on : before.chat,
      exchange: key == 'exchange' ? on : before.exchange,
      pushReady: before.pushReady,
    );
    setState(() => _saving = true);
    try {
      s.notificationSettings.value = await s.notifications.changeSettings({key: on});
    } on ApiException catch (e) {
      s.notificationSettings.value = before;
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _askPermission() async {
    final ok = await widget.services.notifier.requestPermission();
    if (mounted) showSnack(context, ok ? '알림 권한이 켜져 있어요.' : '알림 권한이 없어요. 휴대폰 설정 > 앱 > 스바다 > 알림에서 켤 수 있어요.');
  }

  Widget _switch(String key, IconData icon, String label, String subtitle, bool value) {
    return SettingsRow(
      key: ValueKey('noti-$key'),
      icon: icon,
      label: label,
      subtitle: subtitle,
      showChevron: false,
      onTap: _saving ? null : () => _change(key, !value),
      trailing: Switch(
        value: value,
        activeThumbColor: Sb.onAccent,
        activeTrackColor: Sb.blue,
        onChanged: _saving ? null : (v) => _change(key, v),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.services;
    return Scaffold(
      backgroundColor: Sb.groupedBg,
      appBar: AppBar(
        backgroundColor: Sb.groupedBg,
        title: const Text('알림 설정', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800)),
      ),
      body: ValueListenableBuilder<NotificationSettings>(
        valueListenable: s.notificationSettings,
        builder: (context, n, child) => ListView(padding: const EdgeInsets.only(top: 4, bottom: 32), children: [
          SettingsGroup(title: '받을 알림', children: [
            _switch('like', Icons.favorite_border_rounded, '찜 알림', '누가 내 물건을 관심목록에 담았을 때', n.like),
            _switch('chat', Icons.chat_bubble_outline_rounded, '채팅 알림', '새 채팅 메시지를 받았을 때', n.chat),
            _switch('exchange', Icons.swap_horiz_rounded, '교환 알림', '교환 요청이 오거나 수락 · 거절됐을 때', n.exchange),
          ]),
          SettingsGroup(title: '푸시(앱이 꺼져 있을 때)', children: [
            ValueListenableBuilder<PushState>(
              valueListenable: s.push.state,
              builder: (context, state, child) => SettingsRow(
                icon: state == PushState.registered ? Icons.notifications_active_outlined : Icons.notifications_off_outlined,
                label: 'Android FCM · iOS APNs',
                subtitle: s.push.description,
                showChevron: false,
              ),
            ),
            SettingsRow(
              icon: Icons.verified_user_outlined,
              label: '알림 권한 확인',
              subtitle: '휴대폰에서 스바다 알림을 허용했는지',
              onTap: _askPermission,
            ),
          ]),
          Padding(
            padding: const EdgeInsets.fromLTRB(28, 4, 28, 0),
            child: Text('끈 알림도 알림함에는 남아요. 푸시는 휴대폰 운영체제가 대신 전달해서, 권한과 배터리 설정에 따라 늦거나 안 올 수 있어요.',
                style: sbCaption),
          ),
        ]),
      ),
    );
  }
}
