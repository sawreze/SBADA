import '../util/format.dart';
import 'json.dart';

class AppNotification {
  const AppNotification({
    required this.id,
    required this.type,
    required this.title,
    this.body,
    this.refType,
    this.refId,
    required this.read,
    required this.createdAt,
  });

  final int id;

  /// EXCHANGE_REQUESTED · EXCHANGE_ACCEPTED · EXCHANGE_REJECTED · EXCHANGE_CANCELED · MATCH_SUGGESTED
  final String type;
  final String title;
  final String? body;

  /// ITEM · EXCHANGE · CHAT
  final String? refType;
  final int? refId;
  final bool read;
  final DateTime createdAt;

  String get emoji => switch (type) {
        'EXCHANGE_REQUESTED' => '🔁',
        'EXCHANGE_ACCEPTED' => '🎉',
        'EXCHANGE_REJECTED' => '🙅',
        'EXCHANGE_CANCELED' => '↩️',
        'MATCH_SUGGESTED' => '✨',
        _ => '🔔',
      };

  factory AppNotification.fromJson(Map<String, dynamic> j) => AppNotification(
        id: reqInt(j, 'id'),
        type: reqStr(j, 'type'),
        title: reqStr(j, 'title'),
        body: optStr(j, 'body'),
        refType: optStr(j, 'refType'),
        refId: optInt(j, 'refId'),
        read: optBool(j, 'read'),
        createdAt: parseTime(j['createdAt']),
      );
}

/// 알림 설정(계정정보 > 알림 설정, 서버 GET/PUT /api/me/notification-settings).
///   like = 누가 내 물건을 찜함 · chat = 새 채팅 · exchange = 교환 요청 · 수락 · 거절 · 취소
///   pushReady = 서버에 Firebase 설정이 있어 앱이 꺼져 있을 때도 푸시(FCM/APNs)를 보낼 수 있음
class NotificationSettings {
  const NotificationSettings({this.like = true, this.chat = true, this.exchange = true, this.pushReady = false});

  final bool like;
  final bool chat;
  final bool exchange;
  final bool pushReady;

  factory NotificationSettings.fromJson(Map<String, dynamic> j) => NotificationSettings(
        like: j['like'] != false,
        chat: j['chat'] != false,
        exchange: j['exchange'] != false,
        pushReady: j['pushReady'] == true,
      );

  /// 서버 알림 종류(type)가 켜져 있는지. 설정에 없는 종류(매칭 · 보류)는 항상 true
  bool allows(String type) {
    if (type == 'ITEM_LIKED') return like;
    if (type == 'CHAT_MESSAGE') return chat;
    if (type.startsWith('EXCHANGE')) return exchange;
    return true;
  }
}
