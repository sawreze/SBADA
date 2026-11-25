import '../models/json.dart';
import '../network/api_client.dart';

/// 2026-11-25 교환 추가요금 결제: 토스페이먼츠(테스트 키) · 가상계좌 · 시연 결제
///  앱 → 메인 서버(8097) /api/payments/** → 결제 서버(8098). 키 값은 서버에 있고, 앱은 결제창에 쓸 클라이언트 키만 받는다.
class TossRepository {
  TossRepository(this.api);

  final ApiClient api;

  Future<TossConfig> config() async =>
      TossConfig.fromJson(await api.get('/api/payments/toss/config') as Map<String, dynamic>);

  /// 주문 만들기. method: CARD(토스 결제) · VIRTUAL_ACCOUNT(가상계좌)
  Future<TossOrder> prepare(int exchangeId, String method) async => TossOrder.fromJson(await api
      .post('/api/payments/toss/prepare', body: {'exchangeId': exchangeId, 'method': method}) as Map<String, dynamic>);

  /// 결제창이 돌려준 값으로 승인(카드 → DONE, 가상계좌 → WAITING_FOR_DEPOSIT + 계좌번호)
  Future<TossOrder> confirm({required String paymentKey, required String orderId, required int amount}) async =>
      TossOrder.fromJson(await api.post('/api/payments/toss/confirm',
          body: {'paymentKey': paymentKey, 'orderId': orderId, 'amount': amount},
          timeout: const Duration(seconds: 40)) as Map<String, dynamic>);

  /// 이 교환의 마지막 토스 주문(가상계좌 입금 대기 다시 보기). 없으면 null
  Future<TossOrder?> latest(int exchangeId) async {
    try {
      return TossOrder.fromJson(
          await api.get('/api/payments/toss/orders', query: {'exchangeId': '$exchangeId'}) as Map<String, dynamic>);
    } catch (_) {
      return null;
    }
  }

  /// 토스 키가 없을 때 흐름 확인용 시연 결제(실제 돈 X): 결제 서버 샌드박스 안전결제
  Future<void> demoPay({required int exchangeId, required int amount}) async {
    await api.post('/api/payments/escrow', body: {'exchangeId': exchangeId, 'amount': amount, 'method': 'CARD'});
  }
}

class TossConfig {
  const TossConfig({required this.enabled, this.clientKey, required this.widget, required this.label});

  final bool enabled;
  final String? clientKey;

  /// 결제위젯 연동 키(test_gck_)면 결제위젯 SDK, 아니면 결제창 SDK
  final bool widget;
  final String label;

  factory TossConfig.fromJson(Map<String, dynamic> j) => TossConfig(
        enabled: optBool(j, 'enabled'),
        clientKey: optStr(j, 'clientKey'),
        widget: optBool(j, 'widget'),
        label: optStr(j, 'label') ?? '토스페이먼츠 테스트 결제',
      );
}

class TossOrder {
  const TossOrder({
    required this.orderId,
    required this.orderName,
    required this.amount,
    required this.fee,
    required this.total,
    required this.method,
    required this.status,
    required this.clientKey,
    required this.widget,
    required this.customerKey,
    this.bank,
    this.accountNumber,
    this.holder,
    this.dueDate,
    this.message,
  });

  final String orderId;
  final String orderName;
  final int amount;
  final int fee;
  final int total;
  final String method;

  /// READY · WAITING_FOR_DEPOSIT · DONE · CANCELED · FAILED
  final String status;
  final String clientKey;
  final bool widget;
  final String customerKey;
  final String? bank;
  final String? accountNumber;
  final String? holder;
  final String? dueDate;
  final String? message;

  bool get waitingDeposit => status == 'WAITING_FOR_DEPOSIT';
  bool get done => status == 'DONE';

  factory TossOrder.fromJson(Map<String, dynamic> j) {
    final va = optMap(j, 'virtualAccount');
    return TossOrder(
      orderId: reqStr(j, 'orderId'),
      orderName: optStr(j, 'orderName') ?? '쓰바다 추가요금',
      amount: optInt(j, 'amount') ?? 0,
      fee: optInt(j, 'fee') ?? 0,
      total: optInt(j, 'total') ?? 0,
      method: optStr(j, 'method') ?? 'CARD',
      status: optStr(j, 'status') ?? 'READY',
      clientKey: optStr(j, 'clientKey') ?? '',
      widget: optBool(j, 'widget'),
      customerKey: optStr(j, 'customerKey') ?? 'ANONYMOUS',
      bank: va == null ? null : optStr(va, 'bank'),
      accountNumber: va == null ? null : optStr(va, 'accountNumber'),
      holder: va == null ? null : optStr(va, 'holder'),
      dueDate: va == null ? null : optStr(va, 'dueDate'),
      message: optStr(j, 'message'),
    );
  }
}
