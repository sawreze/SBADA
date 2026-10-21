import 'dart:async';

import 'package:flutter/material.dart';

import '../models/chat.dart';
import '../models/exchange.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/exchange_card.dart';
import '../widgets/my_item_picker.dart';
import '../widgets/net_image.dart';

/// 채팅방. 보내기·받기는 WebSocket, 지난 메시지와 끊겼을 때의 보내기는 REST.
///   보낼 때: 화면에 '보내는 중' 말풍선을 먼저 그리고(clientMsgId), 서버가 같은 clientMsgId 로
///   돌려주면 진짜 메시지로 바꾼다 → 전송 확인(ACK)
class ChatRoomScreen extends StatefulWidget {
  const ChatRoomScreen({super.key, required this.services, required this.roomId});

  final AppServices services;
  final int roomId;

  @override
  State<ChatRoomScreen> createState() => _ChatRoomScreenState();
}

class _ChatRoomScreenState extends State<ChatRoomScreen> {
  ChatRoom? _room;
  final List<ChatMessage> _messages = [];
  final Map<String, int> _pendingByClientId = {};
  final _input = TextEditingController();
  final _scroll = ScrollController();
  StreamSubscription<Map<String, dynamic>>? _sub;
  Object? _error;
  bool _loading = true;
  int _tempId = -1;
  int? _busyExchange;
  bool _wasConnected = true;

  int get _myId => widget.services.session.user?.id ?? -1;

  @override
  void initState() {
    super.initState();
    widget.services.activeRoomId = widget.roomId;
    _sub = widget.services.realtime.events.listen(_onEvent);
    widget.services.realtime.connected.addListener(_onConnectionChanged);
    _load();
  }

  @override
  void dispose() {
    if (widget.services.activeRoomId == widget.roomId) widget.services.activeRoomId = null;
    widget.services.realtime.connected.removeListener(_onConnectionChanged);
    _sub?.cancel();
    _input.dispose();
    _scroll.dispose();
    unawaited(widget.services.chats.markRead(widget.roomId).catchError((_) {}));
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final room = await widget.services.chats.room(widget.roomId);
      final list = await widget.services.chats.messages(widget.roomId);
      if (!mounted) return;
      setState(() {
        _room = room;
        _messages
          ..clear()
          ..addAll(list);
        _loading = false;
      });
      _markRead();
      _scrollToEnd();
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e;
          _loading = false;
        });
      }
    }
  }

  /// 다시 연결되면 끊긴 동안 놓친 메시지를 afterId 로 채운다.
  Future<void> _onConnectionChanged() async {
    final now = widget.services.realtime.connected.value;
    if (now && !_wasConnected) {
      final lastId = _messages.where((m) => m.id > 0).fold<int>(0, (a, m) => m.id > a ? m.id : a);
      try {
        final missed = await widget.services.chats.messages(widget.roomId, afterId: lastId);
        if (mounted) setState(() => missed.forEach(_upsert));
      } catch (_) {}
    }
    _wasConnected = now;
  }

  void _markRead() {
    if (!widget.services.realtime.send({'type': 'chat.read', 'roomId': widget.roomId})) {
      unawaited(widget.services.chats.markRead(widget.roomId).catchError((_) {}));
    }
  }

  void _onEvent(Map<String, dynamic> e) {
    if (!mounted) return;
    switch (e['type']) {
      case 'chat.message':
        final raw = e['message'];
        if (raw is! Map<String, dynamic>) return;
        final msg = ChatMessage.fromJson(raw);
        if (msg.roomId != widget.roomId) return;
        final clientId = e['clientMsgId'];
        setState(() {
          if (clientId is String && _pendingByClientId.containsKey(clientId)) {
            final temp = _pendingByClientId.remove(clientId);
            _messages.removeWhere((m) => m.id == temp);
          }
          _upsert(msg);
        });
        if (msg.senderId != _myId) _markRead();
        _scrollToEnd();
      case 'exchange.updated':
        final raw = e['exchange'];
        if (raw is! Map<String, dynamic>) return;
        final ex = Exchange.fromJson(raw);
        setState(() {
          for (var i = 0; i < _messages.length; i++) {
            if (_messages[i].exchange?.id == ex.id) _messages[i] = _messages[i].copyWithExchange(ex);
          }
        });
      case 'error':
        final clientId = e['clientMsgId'];
        if (clientId is String && _pendingByClientId.containsKey(clientId)) {
          final temp = _pendingByClientId.remove(clientId);
          setState(() => _messages.removeWhere((m) => m.id == temp));
          showSnack(context, '${e['message'] ?? '보내지 못했어요.'}', error: true);
        }
    }
  }

  void _upsert(ChatMessage m) {
    final idx = _messages.indexWhere((x) => x.id == m.id);
    if (idx >= 0) {
      _messages[idx] = m;
    } else {
      _messages.add(m);
    }
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    _input.clear();
    final clientId = 'c${DateTime.now().microsecondsSinceEpoch}';
    final temp = ChatMessage(
      id: _tempId--,
      roomId: widget.roomId,
      senderId: _myId,
      senderNickname: widget.services.session.user?.nickname,
      type: 'TEXT',
      content: text,
      createdAt: DateTime.now(),
      pending: true,
    );
    setState(() {
      _messages.add(temp);
      _pendingByClientId[clientId] = temp.id;
    });
    _scrollToEnd();
    final viaSocket = widget.services.realtime
        .send({'type': 'chat.send', 'roomId': widget.roomId, 'content': text, 'clientMsgId': clientId});
    if (viaSocket) return;
    // WebSocket 이 끊겼으면 REST 로 보낸다
    try {
      final saved = await widget.services.chats.sendRest(widget.roomId, text);
      if (!mounted) return;
      setState(() {
        _pendingByClientId.remove(clientId);
        _messages.removeWhere((m) => m.id == temp.id);
        _upsert(saved);
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _messages.removeWhere((m) => m.id == temp.id));
      _input.text = text; // 쓴 글을 되돌려 준다(11장)
      showSnack(context, e.message, error: true);
    }
  }

  /// 채팅 중 '교환 제안' → 내 물건 고르기 → 교환 요청(상대에게 알림)
  Future<void> _propose() async {
    final room = _room;
    if (room == null) return;
    try {
      final target = await widget.services.items.detail(room.item.id);
      if (!mounted) return;
      final choice = await pickMyItem(context, widget.services, target: target);
      if (choice == null) return;
      await widget.services.exchanges
          .request(targetItemId: target.id, offeredItemId: choice.item.id, message: choice.message);
      widget.services.itemsChanged.value++;
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    }
  }

  Future<void> _respond(Exchange ex, Future<Exchange> Function(int id) action) async {
    setState(() => _busyExchange = ex.id);
    try {
      final updated = await action(ex.id);
      if (!mounted) return;
      setState(() {
        for (var i = 0; i < _messages.length; i++) {
          if (_messages[i].exchange?.id == updated.id) _messages[i] = _messages[i].copyWithExchange(updated);
        }
      });
      widget.services.itemsChanged.value++;
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busyExchange = null);
    }
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.animateTo(_scroll.position.maxScrollExtent + 80,
            duration: const Duration(milliseconds: 250), curve: Curves.easeOut);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final room = _room;
    return Scaffold(
      appBar: AppBar(
        title: Text(room?.partner.nickname ?? '채팅'),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: widget.services.realtime.connected,
            builder: (context, on, child) => Padding(
              padding: const EdgeInsets.only(right: 12),
              child: Icon(Icons.bolt, color: on ? Sb.green : Sb.text3),
            ),
          ),
        ],
      ),
      body: _loading
          ? const LoadingView()
          : _error != null
              ? ErrorView(error: _error!, onRetry: _load)
              : Column(children: [
                  if (room != null) _itemBar(room),
                  Expanded(
                    child: ListView.builder(
                      controller: _scroll,
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                      itemCount: _messages.length,
                      itemBuilder: (context, i) => _bubble(_messages[i]),
                    ),
                  ),
                  _composer(),
                ]),
    );
  }

  Widget _itemBar(ChatRoom room) {
    final canPropose = !room.iAmOwner && room.item.status == 'AVAILABLE';
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 8, 12, 8),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: Sb.line))),
      child: Row(children: [
        NetImage(room.item.imageUrl, size: 44, radius: 10),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(room.item.title, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
            Text('AI ${formatWon(room.item.price)}${room.item.status == 'TRADED' ? ' · 교환완료' : ''}', style: sbCaption),
          ]),
        ),
        if (canPropose)
          FilledButton.tonalIcon(
            style: FilledButton.styleFrom(minimumSize: const Size(0, 38)),
            onPressed: _propose,
            icon: const Icon(Icons.swap_horiz_rounded, size: 18),
            label: const Text('교환 제안'),
          ),
      ]),
    );
  }

  Widget _bubble(ChatMessage m) {
    if (m.type == 'SYSTEM') {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Center(child: Pill(m.content, color: Sb.bg, textColor: Sb.text2)),
      );
    }
    if (m.type == 'PROPOSAL' && m.exchange != null) {
      final ex = m.exchange!;
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: ExchangeCard(
          exchange: ex,
          myId: _myId,
          compact: true,
          busy: _busyExchange == ex.id,
          onAccept: () => _respond(ex, widget.services.exchanges.accept),
          onReject: () => _respond(ex, widget.services.exchanges.reject),
          onCancel: () => _respond(ex, widget.services.exchanges.cancel),
        ),
      );
    }
    final mine = m.senderId == _myId;
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.end, children: [
        if (mine)
          Padding(
            padding: const EdgeInsets.only(right: 6, bottom: 4),
            child: Text(m.pending ? '보내는 중' : _hm(m.createdAt), style: sbCaption),
          ),
        ConstrainedBox(
          constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.68),
          child: Container(
            margin: const EdgeInsets.symmetric(vertical: 3),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: mine ? (m.pending ? Sb.blue.withValues(alpha: 0.6) : Sb.blue) : Sb.bg,
              borderRadius: BorderRadius.only(
                topLeft: const Radius.circular(18),
                topRight: const Radius.circular(18),
                bottomLeft: Radius.circular(mine ? 18 : 4),
                bottomRight: Radius.circular(mine ? 4 : 18),
              ),
            ),
            child: Text(m.content,
                style: TextStyle(color: mine ? Sb.onAccent : Sb.navy, fontSize: 15, height: 1.4)),
          ),
        ),
        if (!mine)
          Padding(
            padding: const EdgeInsets.only(left: 6, bottom: 4),
            child: Text(_hm(m.createdAt), style: sbCaption),
          ),
      ]),
    );
  }

  String _hm(DateTime t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';

  Widget _composer() {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 6, 8, 8),
        child: Row(children: [
          Expanded(
            child: TextField(
              controller: _input,
              minLines: 1,
              maxLines: 4,
              textInputAction: TextInputAction.send,
              onSubmitted: (_) => _send(),
              decoration: InputDecoration(
                hintText: '메시지 보내기',
                filled: true,
                fillColor: Sb.bg,
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(24), borderSide: BorderSide.none),
              ),
            ),
          ),
          const SizedBox(width: 6),
          IconButton.filled(
            onPressed: _send,
            style: IconButton.styleFrom(backgroundColor: Sb.blue),
            icon: const Icon(Icons.arrow_upward_rounded, color: Sb.onAccent),
          ),
        ]),
      ),
    );
  }
}
