import '../models/exchange.dart';
import '../network/api_client.dart';

class ExchangeRepository {
  ExchangeRepository(this.api);

  final ApiClient api;

  /// '바로 바꾸기' — 상대가 수락해야 교환이 완료된다.
  Future<Exchange> request({required int targetItemId, required int offeredItemId, String? message}) async {
    final json = await api.post('/api/exchanges', body: {
      'targetItemId': targetItemId,
      'offeredItemId': offeredItemId,
      'message': message,
    }) as Map<String, dynamic>;
    return Exchange.fromJson(json);
  }

  Future<List<Exchange>> list({required bool received}) async {
    final json = await api.get('/api/exchanges', query: {'box': received ? 'received' : 'sent'})
        as Map<String, dynamic>;
    return (json['exchanges'] as List).map((e) => Exchange.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<Exchange> get(int id) async => Exchange.fromJson(await api.get('/api/exchanges/$id') as Map<String, dynamic>);

  Future<Exchange> accept(int id) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/accept') as Map<String, dynamic>);

  Future<Exchange> reject(int id) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/reject') as Map<String, dynamic>);

  Future<Exchange> cancel(int id) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/cancel') as Map<String, dynamic>);

  // ───── 2026-11-25 수락 → 바로 수락 / 추가요금 요청 · 운송장 · 후기 ─────

  /// 받은 사람: 추가요금 요청(내 물건이 더 쌀 때만)
  Future<Exchange> requestExtraFee(int id, int amount) async => Exchange.fromJson(
      await api.post('/api/exchanges/$id/extra-request', body: {'amount': amount}) as Map<String, dynamic>);

  /// 보낸 사람: 추가요금 요청 수락(→ 결제) · 거절(→ 받은 사람이 다시 선택)
  Future<Exchange> agreeExtraFee(int id) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/extra-request/accept') as Map<String, dynamic>);

  Future<Exchange> declineExtraFee(int id) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/extra-request/decline') as Map<String, dynamic>);

  /// 택배사 목록(맨 앞 TEST = 시연용 테스트 택배)
  static bool? _demo;

  /// 2026-11-25 시연 모드(서버 dev 프로필): 입력칸을 미리 채우고 '시연 결제'를 늘 보여 준다. 한 번만 묻고 기억
  Future<bool> demoMode() async {
    if (_demo != null) return _demo!;
    try {
      final json = await api.get('/api/config/demo', auth: false) as Map<String, dynamic>;
      return _demo = json['easyFlow'] == true;
    } catch (_) {
      return false;
    }
  }

  Future<List<Carrier>> carriers() async {
    final json = await api.get('/api/shipping/carriers', auth: false) as Map<String, dynamic>;
    return (json['carriers'] as List? ?? const [])
        .whereType<Map<String, dynamic>>()
        .map((c) => Carrier('${c['code']}', '${c['name']}'))
        .toList();
  }

  /// 내가 보낸 물건의 운송장 입력(택배사 접수가 확인되면 성공, 아니면 400 INVALID_INVOICE)
  Future<Exchange> submitShipment(int id, {required String carrierCode, required String invoiceNo}) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/shipment',
          body: {'carrierCode': carrierCode, 'invoiceNo': invoiceNo}) as Map<String, dynamic>);

  /// 배송 현황 지금 다시 묻기
  Future<Exchange> refreshShipment(int id) async =>
      Exchange.fromJson(await api.post('/api/exchanges/$id/shipment/refresh') as Map<String, dynamic>);

  /// 거래 후기(두 물건 도착 뒤 한 번)
  Future<void> writeReview(int id, {required int rating, String? content}) async {
    await api.post('/api/exchanges/$id/review', body: {'rating': rating, 'content': content});
  }

  /// 다른 사람 프로필의 후기 목록
  Future<ReviewList> reviewsOf(int userId) async =>
      ReviewList.fromJson(await api.get('/api/users/$userId/reviews', auth: false) as Map<String, dynamic>);
}
