import 'dart:async';

import 'package:flutter/material.dart';

import '../models/app_notification.dart';
import '../models/exchange.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/exchange_card.dart';
import 'chat_room_screen.dart';
import 'item_detail_screen.dart';

/// 알림함 + 받은 교환 요청(수락/거절) + 보낸 교환 요청(취소)
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key, required this.services, this.initialTab = 0});

  final AppServices services;

  /// 0 = 알림, 1 = 받은 요청, 2 = 보낸 요청 (계정정보 메뉴에서 바로 열 때 쓴다)
  final int initialTab;

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

enum _Tab { alerts, received, sent }

class _NotificationsScreenState extends State<NotificationsScreen> {
  late _Tab _tab = _Tab.values[widget.initialTab.clamp(0, _Tab.values.length - 1)];
  late Future<List<Object>> _future;
  StreamSubscription<Map<String, dynamic>>? _sub;
  int? _busyId;

  @override
  void initState() {
    super.initState();
    _load();
    _sub = widget.services.realtime.events.listen((e) {
      final t = e['type'];
      if ((t == 'notification' || t == 'exchange.updated') && mounted) setState(_load);
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  void _load() {
    final s = widget.services;
    _future = switch (_tab) {
      _Tab.alerts => s.notifications.list().then((v) => v.cast<Object>()),
      _Tab.received => s.exchanges.list(received: true).then((v) => v.cast<Object>()),
      _Tab.sent => s.exchanges.list(received: false).then((v) => v.cast<Object>()),
    };
  }

  Future<void> _readAll() async {
    try {
      await widget.services.notifications.markAllRead();
      widget.services.unreadNotifications.value = 0;
      if (mounted) setState(_load);
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    }
  }

  Future<void> _openNotification(AppNotification n) async {
    final s = widget.services;
    if (!n.read) {
      unawaited(s.notifications.markRead(n.id).catchError((_) {}));
      if (s.unreadNotifications.value > 0) s.unreadNotifications.value--;
    }
    if (n.refType == 'ITEM' && n.refId != null) {
      await Navigator.of(context)
          .push(MaterialPageRoute<void>(builder: (_) => ItemDetailScreen(services: s, itemId: n.refId!)));
    } else if (n.refType == 'EXCHANGE' && n.refId != null) {
      await _showExchange(n.refId!);
    }
    if (mounted) setState(_load);
  }

  Future<void> _showExchange(int id) async {
    final s = widget.services;
    Exchange ex;
    try {
      ex = await s.exchanges.get(id);
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
      return;
    }
    if (!mounted) return;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      backgroundColor: Sb.card,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: _exchangeCard(ex, closeSheet: () => Navigator.of(sheetContext).pop()),
        ),
      ),
    );
  }

  Future<void> _act(Exchange ex, Future<Exchange> Function(int) action, String done, {VoidCallback? after}) async {
    setState(() => _busyId = ex.id);
    try {
      await action(ex.id);
      widget.services.itemsChanged.value++;
      if (!mounted) return;
      showSnack(context, done);
      after?.call();
      setState(_load);
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  Widget _exchangeCard(Exchange ex, {VoidCallback? closeSheet}) {
    final s = widget.services;
    return ExchangeCard(
      exchange: ex,
      myId: s.session.user?.id ?? -1,
      busy: _busyId == ex.id,
      onAccept: () => _act(ex, s.exchanges.accept, '🎉 교환이 성사되었어요! 채팅으로 약속을 잡아 보세요.', after: closeSheet),
      onReject: () => _act(ex, s.exchanges.reject, '요청을 거절했어요.', after: closeSheet),
      onCancel: () => _act(ex, s.exchanges.cancel, '요청을 취소했어요.', after: closeSheet),
      onOpenChat: ex.chatRoomId == null
          ? null
          : () {
              closeSheet?.call();
              Navigator.of(context).push(MaterialPageRoute<void>(
                  builder: (_) => ChatRoomScreen(services: s, roomId: ex.chatRoomId!)));
            },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        toolbarHeight: 56,
        title: const Text('알림', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800)),
        actions: [
          if (_tab == _Tab.alerts)
            TextButton(
              onPressed: _readAll,
              child: const Text('모두 읽음', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
            ),
          const SizedBox(width: 4),
        ],
      ),
      body: Column(children: [
        _tabs(),
        Expanded(
          child: FutureBuilder<List<Object>>(
            future: _future,
            builder: (context, snap) {
              if (snap.connectionState != ConnectionState.done && !snap.hasData) return const LoadingView();
              if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
              final list = snap.data ?? const [];
              if (list.isEmpty) {
                return EmptyView(
                  emoji: _tab == _Tab.alerts ? '🔔' : '🔁',
                  title: switch (_tab) {
                    _Tab.alerts => '새 알림이 없어요',
                    _Tab.received => '받은 교환 요청이 없어요',
                    _Tab.sent => '보낸 교환 요청이 없어요',
                  },
                );
              }
              return RefreshIndicator(
                onRefresh: () async {
                  setState(_load);
                  await _future;
                },
                child: ListView.builder(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.only(bottom: 24),
                  itemCount: list.length,
                  itemBuilder: (context, i) {
                    final v = list[i];
                    if (v is AppNotification) return _notificationTile(v);
                    return _exchangeCard(v as Exchange);
                  },
                ),
              );
            },
          ),
        ),
      ]),
    );
  }

  /// 디자인 시안: 회색 캡슐 안에 3칸, 고른 칸은 민트 옅은 바탕
  Widget _tabs() {
    const labels = {_Tab.alerts: '알림', _Tab.received: '받은 요청', _Tab.sent: '보낸 요청'};
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      height: 44,
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(color: Sb.bg, borderRadius: BorderRadius.circular(14)),
      child: Row(children: [
        for (final t in _Tab.values)
          Expanded(
            child: Semantics(
              selected: _tab == t,
              button: true,
              child: Material(
                color: _tab == t ? Sb.blueSoft : Colors.transparent,
                borderRadius: BorderRadius.circular(10),
                child: InkWell(
                  borderRadius: BorderRadius.circular(10),
                  onTap: () => setState(() {
                    _tab = t;
                    _load();
                  }),
                  child: Center(
                    child: Text(labels[t]!,
                        style: TextStyle(
                            fontSize: 14,
                            fontWeight: _tab == t ? FontWeight.w700 : FontWeight.w600,
                            color: _tab == t ? Sb.navy : Sb.text2)),
                  ),
                ),
              ),
            ),
          ),
      ]),
    );
  }

  /// 알림 종류별 동그란 아이콘: 교환 요청 = 민트 채움, 그 밖의 안 읽은 알림 = 옅은 민트, 읽은 알림 = 회색
  Widget _icon(AppNotification n) {
    final strong = n.type == 'EXCHANGE_REQUESTED' && !n.read;
    final icon = switch (n.type) {
      'EXCHANGE_REQUESTED' => Icons.swap_horiz_rounded,
      'EXCHANGE_ACCEPTED' => Icons.check_rounded,
      'EXCHANGE_REJECTED' || 'EXCHANGE_CANCELED' => Icons.close_rounded,
      'ITEM_HELD' => Icons.block_rounded,
      'MATCH_SUGGESTED' => Icons.auto_awesome,
      'CHAT_MESSAGE' => Icons.chat_bubble_outline_rounded,
      _ => Icons.notifications_none_rounded,
    };
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: strong ? Sb.blue : (n.read ? Sb.chip : Sb.blueSoft),
      ),
      child: Icon(icon, size: 22, color: strong ? Sb.onAccent : (n.read ? Sb.text2 : Sb.mintText)),
    );
  }

  Widget _notificationTile(AppNotification n) {
    final body = [
      if (n.body != null) n.body!,
      if (n.type == 'EXCHANGE_REQUESTED') '눌러서 수락 / 거절하기',
    ].join(' · ');
    return InkWell(
      onTap: () => _openNotification(n),
      child: Container(
        color: n.read ? Colors.transparent : Sb.blue.withValues(alpha: Sb.isDark ? 0.08 : 0.14),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          _icon(n),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(n.title,
                  style: TextStyle(fontWeight: n.read ? FontWeight.w600 : FontWeight.w700, fontSize: 15, color: Sb.navy)),
              if (body.isNotEmpty) ...[
                const SizedBox(height: 2),
                Text(body, style: TextStyle(fontSize: 14, height: 1.45, color: n.read ? Sb.text3 : Sb.text2)),
              ],
              const SizedBox(height: 4),
              Text(timeAgo(n.createdAt), style: TextStyle(fontSize: 12, color: Sb.text3)),
            ]),
          ),
        ]),
      ),
    );
  }
}
