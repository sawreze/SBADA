/// 토스 결제창이 돌려준 값. 성공이면 paymentKey · orderId · amount 로 서버가 승인(confirm)한다
class TossCheckoutResult {
  const TossCheckoutResult.success({required this.paymentKey, required this.orderId, required this.amount})
      : error = null;

  const TossCheckoutResult.failure(this.error)
      : paymentKey = null,
        orderId = null,
        amount = null;

  final String? paymentKey;
  final String? orderId;
  final int? amount;
  final String? error;

  bool get ok => paymentKey != null;
}
