import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../repositories/toss_repository.dart';
import '../theme/app_theme.dart';
import 'toss_checkout_result.dart';

export 'toss_checkout_result.dart';

/// 결제를 마치면 토스가 이 주소로 돌아온다 → WebView 가 실제로 열지 않고 가로채서 값만 꺼낸다
const _successUrl = 'http://localhost/sbada/toss/success';
const _failUrl = 'http://localhost/sbada/toss/fail';

/// 토스 결제창을 띄우고 결과를 돌려준다(뒤로 가기 = null).
///  - 결제위젯 연동 키(test_gck_): 결제위젯(카드 · 가상계좌 · 간편결제를 위젯 안에서 고름) + '결제하기' 버튼
///  - API 개별 연동 키(test_ck_): 결제창을 바로 연다(method: CARD · VIRTUAL_ACCOUNT)
Future<TossCheckoutResult?> openTossCheckout(BuildContext context, TossOrder order, {required String method}) =>
    Navigator.of(context).push<TossCheckoutResult>(
        MaterialPageRoute(fullscreenDialog: true, builder: (_) => _TossCheckoutPage(order: order, method: method)));

class _TossCheckoutPage extends StatefulWidget {
  const _TossCheckoutPage({required this.order, required this.method});

  final TossOrder order;
  final String method;

  @override
  State<_TossCheckoutPage> createState() => _TossCheckoutPageState();
}

class _TossCheckoutPageState extends State<_TossCheckoutPage> {
  late final WebViewController _web;
  bool _loading = true;
  bool _done = false;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.white)
      ..addJavaScriptChannel('Sbada', onMessageReceived: (m) => _onMessage(m.message))
      ..setNavigationDelegate(NavigationDelegate(
        onPageFinished: (_) {
          if (mounted) setState(() => _loading = false);
        },
        onNavigationRequest: (r) {
          final url = r.url;
          if (url.startsWith(_successUrl)) {
            final q = Uri.parse(url).queryParameters;
            _finish(TossCheckoutResult.success(
                paymentKey: q['paymentKey'] ?? '',
                orderId: q['orderId'] ?? widget.order.orderId,
                amount: int.tryParse(q['amount'] ?? '') ?? widget.order.total));
            return NavigationDecision.prevent;
          }
          if (url.startsWith(_failUrl)) {
            final q = Uri.parse(url).queryParameters;
            _finish(TossCheckoutResult.failure(q['message'] ?? '결제를 마치지 못했어요.'));
            return NavigationDecision.prevent;
          }
          // 카드사 · 은행 앱으로 넘어가는 주소(intent:// · ispmobile:// 등)는 테스트 결제에서는 열지 않는다
          if (!url.startsWith('http://') && !url.startsWith('https://') && !url.startsWith('about:')) {
            _finish(const TossCheckoutResult.failure('이 결제수단은 다른 앱을 열어야 해서 테스트에서는 쓸 수 없어요. 카드 · 가상계좌로 해 주세요.'));
            return NavigationDecision.prevent;
          }
          return NavigationDecision.navigate;
        },
      ))
      ..loadHtmlString(_html(), baseUrl: 'http://localhost');
  }

  void _onMessage(String raw) {
    try {
      final m = jsonDecode(raw) as Map<String, dynamic>;
      if (m['type'] == 'error') {
        final code = '${m['code'] ?? ''}';
        // 사용자가 결제창을 닫은 것은 실패가 아니라 취소
        if (code == 'USER_CANCEL' || code == 'PAY_PROCESS_CANCELED') {
          _finish(null);
        } else {
          _finish(TossCheckoutResult.failure('${m['message'] ?? '토스 결제창을 열지 못했어요.'}'));
        }
      }
    } catch (_) {}
  }

  void _finish(TossCheckoutResult? r) {
    if (_done || !mounted) return;
    _done = true;
    Navigator.of(context).pop(r);
  }

  String _html() {
    final o = widget.order;
    final cfg = jsonEncode({
      'clientKey': o.clientKey,
      'customerKey': o.customerKey,
      'widget': o.widget,
      'method': widget.method,
      'amount': o.total,
      'orderId': o.orderId,
      'orderName': o.orderName,
      'successUrl': _successUrl,
      'failUrl': _failUrl,
    });
    return '''
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<script src="https://js.tosspayments.com/v2/standard"></script>
<style>
  body{margin:0;padding:12px 8px 96px;font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#fff;color:#191f28}
  .sum{margin:4px 12px 8px;font-size:15px;color:#4e5968} .sum b{font-size:20px;color:#191f28}
  #pay{display:none;position:fixed;left:16px;right:16px;bottom:24px;height:54px;border:0;border-radius:14px;
       background:#3182f6;color:#fff;font-size:17px;font-weight:700}
  #msg{margin:16px;color:#e5484d;font-size:14px}
</style></head><body>
<div class="sum">결제할 금액 <b id="total"></b> <span style="font-size:12px">(추가요금 + 수수료 · 테스트 결제)</span></div>
<div id="payment-method"></div><div id="agreement"></div>
<button id="pay">결제하기</button><div id="msg"></div>
<script>
  var C = $cfg;
  function post(o){ try{ Sbada.postMessage(JSON.stringify(o)); }catch(e){} }
  function fail(e){ post({type:'error', code:(e && e.code) || '', message:(e && e.message) || String(e)}); }
  document.getElementById('total').textContent = C.amount.toLocaleString('ko-KR') + '원';
  (async function(){
    try {
      if (typeof TossPayments === 'undefined') { fail({message:'토스 결제 모듈을 불러오지 못했어요(인터넷 확인).'}); return; }
      var toss = TossPayments(C.clientKey);
      var common = {orderId:C.orderId, orderName:C.orderName, successUrl:C.successUrl, failUrl:C.failUrl};
      if (C.widget) {
        var widgets = toss.widgets({customerKey:C.customerKey});
        await widgets.setAmount({currency:'KRW', value:C.amount});
        await Promise.all([
          widgets.renderPaymentMethods({selector:'#payment-method', variantKey:'DEFAULT'}),
          widgets.renderAgreement({selector:'#agreement'})
        ]);
        var btn = document.getElementById('pay');
        btn.style.display = 'block';
        btn.onclick = async function(){
          try { await widgets.requestPayment(common); } catch(e){ fail(e); }
        };
      } else {
        var payment = toss.payment({customerKey:C.customerKey});
        var req = Object.assign({method:C.method, amount:{currency:'KRW', value:C.amount}}, common);
        if (C.method === 'VIRTUAL_ACCOUNT') req.virtualAccount = {validHours:24, cashReceipt:{type:'미발행'}};
        else req.card = {flowMode:'DEFAULT', useEscrow:false, useCardPoint:false, useAppCardOnly:false};
        await payment.requestPayment(req);
      }
    } catch(e) { fail(e); }
  })();
</script></body></html>''';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('토스페이먼츠 결제'),
        leading: IconButton(icon: const Icon(Icons.close), onPressed: () => _finish(null)),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(26),
          child: Container(
            width: double.infinity,
            color: Sb.blueSoft,
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Text('테스트 결제 · 실제 돈은 나가지 않아요',
                textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: Sb.mintText, fontWeight: FontWeight.w700)),
          ),
        ),
      ),
      body: Stack(children: [
        WebViewWidget(controller: _web),
        if (_loading) const Center(child: CircularProgressIndicator.adaptive()),
      ]),
    );
  }
}
