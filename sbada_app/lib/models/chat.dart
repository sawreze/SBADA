import '../util/format.dart';
import 'exchange.dart';
import 'item.dart';
import 'json.dart';
import 'user.dart';

class ChatRoom {
  const ChatRoom({
    required this.id,
    required this.item,
    required this.partner,
    required this.iAmOwner,
    this.lastMessage,
    required this.lastMessageAt,
    required this.unread,
  });

  final int id;
  final ItemSummary item;

  /// 나 말고 상대방
  final PublicUser partner;
  final bool iAmOwner;
  final String? lastMessage;
  final DateTime lastMessageAt;
  final int unread;

  factory ChatRoom.fromJson(Map<String, dynamic> j) => ChatRoom(
        id: reqInt(j, 'id'),
        item: ItemSummary.fromJson(j['item'] as Map<String, dynamic>),
        partner: PublicUser.fromJson(j['partner'] as Map<String, dynamic>),
        iAmOwner: optBool(j, 'iAmOwner'),
        lastMessage: optStr(j, 'lastMessage'),
        lastMessageAt: parseTime(j['lastMessageAt']),
        unread: optInt(j, 'unread') ?? 0,
      );
}

class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.roomId,
    this.senderId,
    this.senderNickname,
    required this.type,
    required this.content,
    this.exchange,
    required this.createdAt,
    this.pending = false,
  });

  /// 보내는 중인 메시지는 음수 임시 번호를 쓴다
  final int id;
  final int roomId;
  final int? senderId;
  final String? senderNickname;

  /// TEXT · PROPOSAL(교환 제안 카드) · SYSTEM(안내)
  final String type;
  final String content;
  final Exchange? exchange;
  final DateTime createdAt;
  final bool pending;

  ChatMessage copyWithExchange(Exchange e) => ChatMessage(
        id: id,
        roomId: roomId,
        senderId: senderId,
        senderNickname: senderNickname,
        type: type,
        content: content,
        exchange: e,
        createdAt: createdAt,
      );

  factory ChatMessage.fromJson(Map<String, dynamic> j) {
    final ex = optMap(j, 'exchange');
    return ChatMessage(
      id: reqInt(j, 'id'),
      roomId: reqInt(j, 'roomId'),
      senderId: optInt(j, 'senderId'),
      senderNickname: optStr(j, 'senderNickname'),
      type: optStr(j, 'type') ?? 'TEXT',
      content: optStr(j, 'content') ?? '',
      exchange: ex == null ? null : Exchange.fromJson(ex),
      createdAt: parseTime(j['createdAt']),
    );
  }
}
