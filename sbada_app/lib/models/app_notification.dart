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
