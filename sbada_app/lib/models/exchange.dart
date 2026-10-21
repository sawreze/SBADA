import '../util/format.dart';
import 'item.dart';
import 'json.dart';
import 'user.dart';

class Exchange {
  const Exchange({
    required this.id,
    required this.status,
    this.message,
    required this.requester,
    required this.targetOwner,
    required this.offeredItem,
    required this.targetItem,
    required this.priceDiffPercent,
    this.chatRoomId,
    this.closeReason,
    required this.createdAt,
    this.myRole,
  });

  final int id;

  /// REQUESTED · ACCEPTED · REJECTED · CANCELED
  final String status;
  final String? message;
  final PublicUser requester;
  final PublicUser targetOwner;

  /// 요청한 사람이 내놓은 물건
  final ItemSummary offeredItem;

  /// 요청한 사람이 원하는 물건(받은 사람의 물건)
  final ItemSummary targetItem;
  final int priceDiffPercent;
  final int? chatRoomId;
  final String? closeReason;
  final DateTime createdAt;

  /// REQUESTER · RECEIVER (서버가 내 기준으로 계산. 실시간 이벤트에서는 비어 있을 수 있다)
  final String? myRole;

  bool get isPending => status == 'REQUESTED';

  bool isReceiver(int myId) => targetOwner.id == myId;
  bool isRequester(int myId) => requester.id == myId;

  String get statusLabel => switch (status) {
        'REQUESTED' => '수락 대기',
        'ACCEPTED' => '교환 성사',
        'REJECTED' => '거절됨',
        'CANCELED' => '취소됨',
        _ => status,
      };

  factory Exchange.fromJson(Map<String, dynamic> j) => Exchange(
        id: reqInt(j, 'id'),
        status: reqStr(j, 'status'),
        message: optStr(j, 'message'),
        requester: PublicUser.fromJson(j['requester'] as Map<String, dynamic>),
        targetOwner: PublicUser.fromJson(j['targetOwner'] as Map<String, dynamic>),
        offeredItem: ItemSummary.fromJson(j['offeredItem'] as Map<String, dynamic>),
        targetItem: ItemSummary.fromJson(j['targetItem'] as Map<String, dynamic>),
        priceDiffPercent: optInt(j, 'priceDiffPercent') ?? 0,
        chatRoomId: optInt(j, 'chatRoomId'),
        closeReason: optStr(j, 'closeReason'),
        createdAt: parseTime(j['createdAt']),
        myRole: optStr(j, 'myRole'),
      );
}
