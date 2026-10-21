import 'dart:async';

import 'package:flutter/material.dart';

import '../models/chat.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import 'chat_room_screen.dart';

class ChatListScreen extends StatefulWidget {
  const ChatListScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<ChatListScreen> createState() => _ChatListScreenState();
}

class _ChatListScreenState extends State<ChatListScreen> {
  late Future<List<ChatRoom>> _future;

  /// 칩 필터: 0 전체 · 1 안 읽음 · 2 내 물건 · 3 상대 물건
  int _filter = 0;
  static const _filters = ['전체', '안 읽음', '내 물건', '상대 물건'];

  List<ChatRoom> _apply(List<ChatRoom> rooms) => switch (_filter) {
        1 => rooms.where((r) => r.unread > 0).toList(),
        2 => rooms.where((r) => r.iAmOwner).toList(),
        3 => rooms.where((r) => !r.iAmOwner).toList(),
        _ => rooms,
      };

  Widget _chips() {
    return SizedBox(
      height: 50,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
        children: [
          for (var i = 0; i < _filters.length; i++)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Material(
                color: _filter == i ? Sb.navy : Sb.chip,
                shape: const StadiumBorder(),
                child: InkWell(
                  customBorder: const StadiumBorder(),
                  onTap: () => setState(() => _filter = i),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                    child: Text(_filters[i],
                        style: TextStyle(
                            fontSize: 14,
                            fontWeight: _filter == i ? FontWeight.w800 : FontWeight.w600,
                            color: _filter == i ? Sb.base : Sb.navy)),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  @override
  void initState() {
    super.initState();
    _load();
    // 새 메시지·교환 상태 변경(WebSocket)이나 채팅 탭 열기 → 목록(마지막 메시지·안 읽은 수)을 다시 읽는다
    widget.services.chatsChanged.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.services.chatsChanged.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(_load);
  }

  void _load() => _future = widget.services.chats.rooms();

  Future<void> _open(ChatRoom room) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(
        builder: (_) => ChatRoomScreen(services: widget.services, roomId: room.id)));
    if (mounted) setState(_load);
    unawaited(widget.services.refreshBadges());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('채팅'),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: widget.services.realtime.connected,
            builder: (context, on, child) => Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Pill(on ? '실시간 연결됨' : '연결 중…',
                  color: on ? Sb.greenSoft : Sb.bg, textColor: on ? Sb.green : Sb.text3, icon: Icons.bolt),
            ),
          ),
        ],
      ),
      body: Column(children: [
        _chips(),
        Expanded(child: FutureBuilder<List<ChatRoom>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done && !snap.hasData) return const LoadingView();
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
          final rooms = _apply(snap.data ?? const []);
          if (rooms.isEmpty) {
            return EmptyView(
                emoji: '💬',
                title: _filter == 0 ? '아직 채팅이 없어요' : '${_filters[_filter]} 채팅이 없어요',
                message: _filter == 0 ? '마음에 드는 물건에서 채팅하기를 눌러 보세요.' : null);
          }
          return RefreshIndicator(
            onRefresh: () async {
              setState(_load);
              await _future;
            },
            child: ListView.builder(
              // 방이 몇 개 없어 화면보다 짧아도 당겨서 새로고침이 되게 한다
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(bottom: Sb.tabBarSpace),
              itemCount: rooms.length,
              itemBuilder: (context, i) => _roomRow(rooms[i]),
            ),
          );
        },
      )),
      ]),
    );
  }

  /// 디자인 시안(채팅): [이니셜 동그라미] 닉네임 · 동네 · 시간 / 마지막 메시지 / 물건 이름      (안 읽은 수)
  Widget _roomRow(ChatRoom r) {
    final unread = r.unread > 0;
    final dong = r.partner.region.isEmpty ? '' : '${r.partner.region.split(' ').last} · ';
    return InkWell(
      onTap: () => _open(r),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(children: [
          Container(
            width: 52,
            height: 52,
            alignment: Alignment.center,
            decoration: BoxDecoration(shape: BoxShape.circle, color: unread ? Sb.blueSoft : Sb.chip),
            child: Text(r.partner.nickname.characters.first,
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: unread ? Sb.mintText : Sb.text2)),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(crossAxisAlignment: CrossAxisAlignment.baseline, textBaseline: TextBaseline.alphabetic, children: [
                Flexible(
                  child: Text(r.partner.nickname,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Sb.navy)),
                ),
                const SizedBox(width: 6),
                Text('$dong${timeAgo(r.lastMessageAt)}', style: TextStyle(fontSize: 12, color: Sb.text3)),
              ]),
              const SizedBox(height: 3),
              Text(r.lastMessage ?? '대화를 시작해 보세요',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 14, color: unread ? Sb.navy : Sb.text2)),
              const SizedBox(height: 3),
              Text(r.iAmOwner ? '내 물건 · ${r.item.title}' : r.item.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 12, color: unread ? Sb.mintText : Sb.text3)),
            ]),
          ),
          if (unread) ...[
            const SizedBox(width: 8),
            Container(
              constraints: const BoxConstraints(minWidth: 22),
              height: 22,
              padding: const EdgeInsets.symmetric(horizontal: 6),
              alignment: Alignment.center,
              decoration: BoxDecoration(color: Sb.blue, borderRadius: BorderRadius.circular(11)),
              child: Text('${r.unread}',
                  style: const TextStyle(color: Sb.onAccent, fontSize: 12, fontWeight: FontWeight.w800)),
            ),
          ],
        ]),
      ),
    );
  }
}
