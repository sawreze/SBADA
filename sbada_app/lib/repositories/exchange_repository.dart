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
}
