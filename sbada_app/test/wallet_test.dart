import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sbada/main.dart';
import 'package:sbada/models/wallet.dart';
import 'package:sbada/network/api_client.dart';
import 'package:sbada/network/realtime_client.dart';
import 'package:sbada/network/session.dart';
import 'package:sbada/repositories/wallet_repository.dart';
import 'package:sbada/screens/wallet_screen.dart';
import 'package:sbada/services/app_services.dart';
import 'package:sbada/services/local_notifier.dart';

import 'widget_test.dart' show phoneSize;

/// 시연용 지갑: 계좌를 고르고 금액을 넣어 출금하면 "출금되었습니다" 창이 뜬다(서버는 MockClient)
void main() {
  test('수수료는 보내는 사람이 5% 더 낸다(원 단위 반올림)', () {
    expect(walletFee(10000, 5), 500);
    expect(walletFee(12345, 5), 617);
  });

  testWidgets('출금하기 → 출금되었습니다', (tester) async {
    phoneSize(tester);
    var balance = 100000;
    final requests = <String>[];
    Map<String, dynamic> tx(String type, int amount, int after, String memo) => {
          'id': 2,
          'type': type,
          'typeLabel': type == 'WITHDRAW' ? '출금' : '시연 기본 잔액',
          'amount': amount,
          'fee': 0,
          'balanceAfter': after,
          'methodLabel': null,
          'memo': memo,
          'createdAt': '2026-11-20T04:00:00Z',
        };
    final client = MockClient((req) async {
      requests.add('${req.method} ${req.url.path}');
      Map<String, dynamic> body;
      if (req.method == 'GET' && req.url.path == '/api/wallet') {
        body = {
          'demo': true,
          'balance': balance,
          'feePercent': 5,
          'minAmount': 1000,
          'maxAmount': 1000000,
          'accounts': [
            {'id': 7, 'bankCode': 'KAKAOBANK', 'bankName': '카카오뱅크', 'accountMasked': '****5678', 'holder': '임해인'}
          ],
          'transactions': [tx('DEMO_CHARGE', 100000, 100000, '시연용 기본 잔액')],
          'banks': [
            {'code': 'KAKAOBANK', 'label': '카카오뱅크'},
            {'code': 'TOSSBANK', 'label': '토스뱅크'}
          ],
          'methods': [
            {'code': 'CARD', 'label': '신용·체크카드'},
            {'code': 'KAKAOPAY', 'label': '카카오페이'}
          ],
        };
      } else if (req.method == 'POST' && req.url.path == '/api/wallet/withdraw') {
        final j = jsonDecode(req.body) as Map<String, dynamic>;
        expect(j['accountId'], 7);
        final amount = j['amount'] as int;
        balance -= amount;
        body = {'message': '출금되었습니다', 'balance': balance, 'transaction': tx('WITHDRAW', -amount, balance, '카카오뱅크 ****5678')};
      } else {
        return http.Response('{}', 404);
      }
      return http.Response.bytes(utf8.encode(jsonEncode(body)), 200, headers: {'content-type': 'application/json'});
    });
    final session = Session(MemoryStore());
    final api = ApiClient(baseUrl: 'http://test', session: session, client: client);
    final services = AppServices(
      session: session,
      api: api,
      realtime: RealtimeClient(wsUrl: 'ws://test/ws', session: session),
      notifier: LocalNotifier(),
    );

    await tester.pumpWidget(SbadaApp(
      services: services,
      home: WalletScreen(services: services, repository: WalletRepository(api)),
    ));
    await tester.pumpAndSettle();
    expect(find.text('100,000원'), findsWidgets);
    expect(find.text('카카오뱅크 ****5678'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('wallet-withdraw')));
    await tester.pumpAndSettle();
    expect(find.text('출금하기'), findsWidgets);

    await tester.enterText(find.byKey(const ValueKey('wallet-amount')), '30000');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const ValueKey('wallet-withdraw-submit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('wallet-withdraw-submit')));
    await tester.pumpAndSettle();

    expect(find.text('출금되었습니다'), findsOneWidget);
    expect(requests, contains('POST /api/wallet/withdraw'));
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();
    expect(find.text('70,000원'), findsWidgets);
  });
}
