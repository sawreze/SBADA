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
    this.extraAmount = 0,
    this.extraDirection,
    this.paymentStatus = 'NONE',
    this.extraRequestStatus = 'NONE',
    this.canRequestExtra = false,
    this.dealStage = 'NONE',
    this.shipDeadline,
    this.completedAt,
    this.shipments = const [],
    this.iReviewed = false,
    this.otherReviewed = false,
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

  // ── 2026-11-25 추가요금 요청 · 결제 · 운송장 · 후기 ──
  /// 추가요금(원). 0 이면 없음
  final int extraAmount;

  /// 보는 사람 기준: I_PAY(내가 냄) · I_RECEIVE(내가 받음) · null
  final String? extraDirection;

  /// NONE · PAID_HELD(결제되어 보관 중) · RELEASED(정산됨) · REFUND_PENDING · REFUNDED
  final String paymentStatus;

  /// NONE · REQUESTED(받은 사람이 요청) · AGREED(보낸 사람이 수락 → 결제 대기) · DECLINED
  final String extraRequestStatus;

  /// 지금 내가 '추가요금 요청'을 할 수 있는지(받은 사람 · 내 물건이 더 쌀 때)
  final bool canRequestExtra;

  /// NONE · SHIPPING(운송장 · 배송) · COMPLETED(두 물건 도착) · EXPIRED(7일 만료)
  final String dealStage;
  final DateTime? shipDeadline;
  final DateTime? completedAt;
  final List<Shipment> shipments;
  final bool iReviewed;
  final bool otherReviewed;

  bool get isPending => status == 'REQUESTED';
  bool get iPay => extraDirection == 'I_PAY';
  bool get waitingPayment => isPending && extraRequestStatus == 'AGREED' && paymentStatus != 'PAID_HELD';
  Shipment? get myShipment => _ship(true);
  Shipment? get otherShipment => _ship(false);

  Shipment? _ship(bool mine) {
    for (final s in shipments) {
      if (s.mine == mine) return s;
    }
    return null;
  }

  bool isReceiver(int myId) => targetOwner.id == myId;
  bool isRequester(int myId) => requester.id == myId;

  String get statusLabel => switch (status) {
        'REQUESTED' => extraRequestStatus == 'REQUESTED'
            ? '추가요금 요청됨'
            : extraRequestStatus == 'AGREED'
                ? '결제 대기'
                : '수락 대기',
        'ACCEPTED' => switch (dealStage) {
            'SHIPPING' => '배송 중',
            'COMPLETED' => '거래 완료',
            _ => '교환 성사',
          },
        'CANCELED' when dealStage == 'EXPIRED' => '기간 만료',
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
        extraAmount: optInt(j, 'extraAmount') ?? 0,
        extraDirection: optStr(j, 'extraDirection'),
        paymentStatus: optStr(j, 'paymentStatus') ?? 'NONE',
        extraRequestStatus: optStr(j, 'extraRequestStatus') ?? 'NONE',
        canRequestExtra: optBool(j, 'canRequestExtra'),
        dealStage: optStr(j, 'dealStage') ?? 'NONE',
        shipDeadline: j['shipDeadline'] == null ? null : parseTime(j['shipDeadline']),
        completedAt: j['completedAt'] == null ? null : parseTime(j['completedAt']),
        shipments: objList(j, 'shipments', Shipment.fromJson),
        iReviewed: optBool(j, 'iReviewed'),
        otherReviewed: optBool(j, 'otherReviewed'),
      );
}

/// 운송장 한 장(2026-11-25). mine = 내가 보낸 물건
class Shipment {
  const Shipment({
    required this.senderId,
    required this.mine,
    required this.carrierName,
    required this.invoiceMasked,
    required this.status,
    required this.level,
    required this.levelLabel,
    this.lastDetail,
    this.lastWhere,
    this.lastTime,
  });

  final int senderId;
  final bool mine;
  final String carrierName;
  final String invoiceMasked;

  /// VERIFIED(접수 확인) · IN_TRANSIT(이동 중) · DELIVERED(배송 완료)
  final String status;

  /// 1 준비 · 2 집화 · 3 배송 중 · 4 지점 도착 · 5 배송 출발 · 6 배송 완료
  final int level;
  final String levelLabel;
  final String? lastDetail;
  final String? lastWhere;
  final String? lastTime;

  bool get delivered => status == 'DELIVERED';

  factory Shipment.fromJson(Map<String, dynamic> j) => Shipment(
        senderId: optInt(j, 'senderId') ?? 0,
        mine: optBool(j, 'mine'),
        carrierName: optStr(j, 'carrierName') ?? '',
        invoiceMasked: optStr(j, 'invoiceMasked') ?? '',
        status: optStr(j, 'status') ?? 'VERIFIED',
        level: optInt(j, 'level') ?? 0,
        levelLabel: optStr(j, 'levelLabel') ?? '',
        lastDetail: optStr(j, 'lastDetail'),
        lastWhere: optStr(j, 'lastWhere'),
        lastTime: optStr(j, 'lastTime'),
      );
}

/// 택배사(스마트택배 코드). TEST = 시연용 테스트 택배
class Carrier {
  const Carrier(this.code, this.name);

  final String code;
  final String name;
}

/// 거래 후기 한 개(프로필 아래 목록)
class Review {
  const Review({required this.id, required this.rating, this.content, required this.createdAt, required this.writer});

  final int id;
  final int rating;
  final String? content;
  final DateTime createdAt;
  final PublicUser writer;

  factory Review.fromJson(Map<String, dynamic> j) => Review(
        id: reqInt(j, 'id'),
        rating: optInt(j, 'rating') ?? 0,
        content: optStr(j, 'content'),
        createdAt: parseTime(j['createdAt']),
        writer: PublicUser.fromJson((j['writer'] as Map<String, dynamic>?) ?? const {'id': 0, 'nickname': '알 수 없음'}),
      );
}

class ReviewList {
  const ReviewList({required this.count, required this.average, required this.reviews});

  final int count;
  final double average;
  final List<Review> reviews;

  factory ReviewList.fromJson(Map<String, dynamic> j) => ReviewList(
        count: optInt(j, 'count') ?? 0,
        average: optDouble(j, 'average'),
        reviews: objList(j, 'reviews', Review.fromJson),
      );
}
