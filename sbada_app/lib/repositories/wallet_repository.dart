import '../models/wallet.dart';
import '../network/api_client.dart';

/// 결제 서비스 API. 앱은 메인 서버(게이트웨이)로 보내고, 메인 서버가 결제 서비스(8098)로 넘긴다.
class WalletRepository {
  WalletRepository(this.api);

  final ApiClient api;

  Future<Wallet> wallet() async => Wallet.fromJson(await api.get('/api/wallet') as Map<String, dynamic>);

  Future<WalletAccount> addAccount({required String bankCode, required String accountNumber, required String holder}) async =>
      WalletAccount.fromJson(await api.post('/api/wallet/accounts',
          body: {'bankCode': bankCode, 'accountNumber': accountNumber, 'holder': holder}) as Map<String, dynamic>);

  Future<void> deleteAccount(int id) async => api.delete('/api/wallet/accounts/$id');

  Future<WalletResult> withdraw({required int accountId, required int amount}) async => WalletResult.fromJson(
      await api.post('/api/wallet/withdraw', body: {'accountId': accountId, 'amount': amount}) as Map<String, dynamic>);

  Future<WalletResult> charge({required String method, required int amount}) async => WalletResult.fromJson(
      await api.post('/api/wallet/charge', body: {'method': method, 'amount': amount}) as Map<String, dynamic>);

  /// 교환 추가 요금: 금액 + 수수료(5%)를 결제수단으로 내고, 상대 지갑에 금액이 들어간다
  Future<WalletResult> payExtra({required int toUserId, required int amount, required String method, String? memo}) async =>
      WalletResult.fromJson(await api.post('/api/payments/extra', body: {
        'toUserId': toUserId,
        'amount': amount,
        'method': method,
        if (memo != null && memo.isNotEmpty) 'memo': memo,
      }) as Map<String, dynamic>);
}
