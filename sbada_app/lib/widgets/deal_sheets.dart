import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/exchange.dart';
import '../network/api_exception.dart';
import '../payments/toss_checkout.dart';
import '../repositories/toss_repository.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import 'common.dart';

// 2026-11-25 사용자 요청: 교환 수락 → 바로 수락 / 추가요금 요청 → 결제(가상계좌 · 토스) → 운송장 → 거래 후기
// 채팅방의 교환 카드 · 거래 카드(deal_card.dart)가 이 아래쪽 창(시트)들을 연다.

Future<T?> _sheet<T>(BuildContext context, Widget Function(BuildContext) builder) => showModalBottomSheet<T>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Sb.card,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (context) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: SingleChildScrollView(padding: const EdgeInsets.fromLTRB(20, 10, 20, 24), child: builder(context)),
      ),
    );

Widget _handle() => Center(
      child: Container(
        width: 40,
        height: 5,
        margin: const EdgeInsets.only(bottom: 14),
        decoration: BoxDecoration(color: Sb.line, borderRadius: BorderRadius.circular(3)),
      ),
    );

Widget _title(String text, [String? sub]) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(text, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Sb.navy)),
      if (sub != null) ...[
        const SizedBox(height: 6),
        Text(sub, style: TextStyle(fontSize: 14, height: 1.45, color: Sb.text2)),
      ],
      const SizedBox(height: 16),
    ]);

/// 고르는 줄 하나(아이콘 · 제목 · 설명). enabled=false 면 흐리게 + 이유
class _Choice extends StatelessWidget {
  const _Choice({super.key, required this.icon, required this.title, this.subtitle, this.onTap, this.enabled = true, this.badge});

  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback? onTap;
  final bool enabled;
  final String? badge;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: enabled ? 1 : 0.45,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Material(
          color: Sb.bg,
          borderRadius: BorderRadius.circular(16),
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: enabled ? onTap : null,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              child: Row(children: [
                Icon(icon, color: Sb.mintText, size: 26),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Row(children: [
                      Flexible(
                          child: Text(title, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Sb.navy))),
                      if (badge != null) ...[const SizedBox(width: 6), Pill(badge!, color: Sb.blueSoft, textColor: Sb.mintText)],
                    ]),
                    if (subtitle != null) ...[
                      const SizedBox(height: 3),
                      Text(subtitle!, style: TextStyle(fontSize: 13, height: 1.4, color: Sb.text2)),
                    ],
                  ]),
                ),
                if (enabled) Icon(Icons.chevron_right_rounded, color: Sb.text3),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

// ───────────── ① 수락 → 바로 수락 / 추가요금 요청 ─────────────

/// 받은 사람이 '수락'을 누르면: 바로 수락 · 추가요금 요청 중 고른다. 바뀐 교환을 돌려준다(닫으면 null)
Future<Exchange?> showAcceptChoice(BuildContext context, AppServices services, Exchange e) async {
  final choice = await _sheet<String>(
    context,
    (c) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _handle(),
      _title('어떻게 수락할까요?', '${e.requester.nickname}님의 ‘${e.offeredItem.title}’ ↔ 내 ‘${e.targetItem.title}’'),
      _Choice(
        key: const ValueKey('accept-now'),
        icon: Icons.check_circle_rounded,
        title: '바로 수락',
        subtitle: '추가요금 없이 교환을 수락해요. 수락하면 7일 안에 운송장을 입력해요.',
        onTap: () => Navigator.pop(c, 'now'),
      ),
      _Choice(
        key: const ValueKey('accept-extra'),
        icon: Icons.add_card_rounded,
        title: '추가요금 요청',
        subtitle: e.canRequestExtra
            ? '내 물건(${formatWon(e.targetItem.price)})이 상대 물건(${formatWon(e.offeredItem.price)})보다 싸요. 차액을 정해 요청할 수 있어요.'
            : '추가요금은 물건 가격이 더 낮은 쪽만 요청할 수 있어요. (내 물건 ${formatWon(e.targetItem.price)} · 상대 물건 ${formatWon(e.offeredItem.price)})',
        enabled: e.canRequestExtra,
        onTap: () => Navigator.pop(c, 'extra'),
      ),
    ]),
  );
  if (!context.mounted || choice == null) return null;
  try {
    if (choice == 'now') return await services.exchanges.accept(e.id);
    final amount = await _askAmount(context, e);
    if (amount == null || !context.mounted) return null;
    final updated = await services.exchanges.requestExtraFee(e.id, amount);
    if (context.mounted) showSnack(context, '추가요금 ${formatWon(amount)}을 요청했어요. 상대가 수락하면 결제 뒤 교환이 진행돼요.');
    return updated;
  } on ApiException catch (err) {
    if (context.mounted) showSnack(context, err.message, error: true);
    return null;
  }
}

/// 추가요금 금액 입력(기본값 = 두 물건의 가격 차이)
Future<int?> _askAmount(BuildContext context, Exchange e) {
  final diff = (e.offeredItem.price - e.targetItem.price).clamp(1000, 1000000).toInt();
  final ctrl = TextEditingController(text: '${(diff ~/ 1000) * 1000}');
  String? error;
  return _sheet<int>(
    context,
    (c) => StatefulBuilder(
      builder: (c, setState) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        _handle(),
        _title('추가요금 요청', '상대가 수락하고 결제하면 교환이 진행돼요. 돈은 두 물건이 모두 도착할 때까지 쓰바다가 보관해요.'),
        TextField(
          key: const ValueKey('extra-amount'),
          controller: ctrl,
          autofocus: true,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: sbInput('금액(원)', hint: '1,000원 ~ 1,000,000원', errorText: error),
        ),
        const SizedBox(height: 8),
        Text('두 물건의 가격 차이: ${formatWon(e.offeredItem.price - e.targetItem.price)}', style: sbCaption),
        const SizedBox(height: 16),
        FilledButton(
          key: const ValueKey('extra-amount-send'),
          onPressed: () {
            final v = int.tryParse(ctrl.text) ?? 0;
            if (v < 1000 || v > 1000000) {
              setState(() => error = '1,000원 이상 1,000,000원 이하로 입력해 주세요.');
              return;
            }
            Navigator.pop(c, v);
          },
          child: const Text('요청 보내기'),
        ),
      ]),
    ),
  );
}

// ───────────── ② 결제: 가상계좌 · 결제(토스) ─────────────

/// 추가요금 결제 창. 결제가 확인되면 서버가 교환을 자동으로 진행한다(채팅 카드가 실시간으로 바뀜)
Future<void> showDealPaySheet(BuildContext context, AppServices services, Exchange e) async {
  final toss = TossRepository(services.api);
  TossConfig? cfg;
  try {
    cfg = await toss.config();
  } on ApiException {
    cfg = null;
  }
  if (!context.mounted) return;
  final fee = (e.extraAmount * 5 / 100).round();
  final action = await _sheet<String>(
    context,
    (c) => _PayChooser(amount: e.extraAmount, fee: fee, tossEnabled: cfg?.enabled ?? false),
  );
  if (action == null || !context.mounted) return;
  try {
    switch (action) {
      case 'va':
        await _payToss(context, toss, e, 'VIRTUAL_ACCOUNT');
      case 'toss':
        await _payToss(context, toss, e, 'CARD');
      case 'demo':
        await toss.demoPay(exchangeId: e.id, amount: e.extraAmount);
        if (context.mounted) showSnack(context, '시연 결제가 확인되었어요(실제 돈 X). 교환이 진행돼요.');
    }
  } on ApiException catch (err) {
    if (context.mounted) showSnack(context, err.message, error: true);
  }
}

class _PayChooser extends StatefulWidget {
  const _PayChooser({required this.amount, required this.fee, required this.tossEnabled});

  final int amount;
  final int fee;
  final bool tossEnabled;

  @override
  State<_PayChooser> createState() => _PayChooserState();
}

class _PayChooserState extends State<_PayChooser> {
  bool _methods = false; // '결제'를 누르면 결제수단 목록

  @override
  Widget build(BuildContext context) {
    final total = widget.amount + widget.fee;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _handle(),
      _title('추가요금 결제', '${formatWon(widget.amount)} + 수수료 ${formatWon(widget.fee)} = ${formatWon(total)}\n'
          '두 물건이 모두 도착하면 상대에게 전달되고, 거래가 취소되면 전액 환불돼요.'),
      if (!_methods) ...[
        _Choice(
          key: const ValueKey('pay-va'),
          icon: Icons.account_balance_rounded,
          title: '가상계좌',
          subtitle: widget.tossEnabled
              ? '내 이름으로 발급된 계좌에 입금하면, 입금이 확인되는 대로 교환이 진행돼요.'
              : '토스 테스트 키가 있어야 발급돼요(결제_택배_키_설정.command).',
          enabled: widget.tossEnabled,
          onTap: () => Navigator.pop(context, 'va'),
        ),
        _Choice(
          key: const ValueKey('pay-methods'),
          icon: Icons.credit_card_rounded,
          title: '결제',
          subtitle: '토스 · 카카오페이 · 카드 등',
          onTap: () => setState(() => _methods = true),
        ),
        if (!widget.tossEnabled)
          _Choice(
            key: const ValueKey('pay-demo'),
            icon: Icons.science_rounded,
            title: '시연 결제',
            badge: '실제 돈 X',
            subtitle: '키 없이 흐름만 확인해요. 결제된 것으로 처리돼요.',
            onTap: () => Navigator.pop(context, 'demo'),
          ),
      ] else ...[
        _Choice(
          key: const ValueKey('pay-toss'),
          icon: Icons.bolt_rounded,
          title: '토스페이먼츠',
          badge: '테스트',
          subtitle: widget.tossEnabled ? '토스 결제창에서 카드 · 간편결제로 결제해요. (실제 돈은 나가지 않아요)' : '토스 테스트 키가 아직 없어요.',
          enabled: widget.tossEnabled,
          onTap: () => Navigator.pop(context, 'toss'),
        ),
        const _Choice(icon: Icons.chat_bubble_rounded, title: '카카오페이', subtitle: '준비 중이에요', enabled: false),
        const _Choice(icon: Icons.payment_rounded, title: '네이버페이', subtitle: '준비 중이에요', enabled: false),
        const _Choice(icon: Icons.credit_card_rounded, title: '신용 · 체크카드(직접)', subtitle: '준비 중이에요 — 토스 결제창에서 카드로 낼 수 있어요', enabled: false),
        TextButton(onPressed: () => setState(() => _methods = false), child: const Text('← 뒤로')),
      ],
    ]);
  }
}

Future<void> _payToss(BuildContext context, TossRepository toss, Exchange e, String method) async {
  // 이미 발급된 가상계좌가 입금을 기다리는 중이면 그 계좌를 다시 보여 준다
  final last = await toss.latest(e.id);
  if (last != null && last.waitingDeposit) {
    if (context.mounted) await showVirtualAccount(context, last);
    return;
  }
  final order = await toss.prepare(e.id, method);
  if (order.waitingDeposit) {
    if (context.mounted) await showVirtualAccount(context, order);
    return;
  }
  if (!context.mounted) return;
  final r = await openTossCheckout(context, order, method: method);
  if (r == null || !context.mounted) return; // 사용자가 닫음
  if (!r.ok) {
    showSnack(context, r.error ?? '결제를 마치지 못했어요.', error: true);
    return;
  }
  final confirmed = await toss.confirm(paymentKey: r.paymentKey!, orderId: r.orderId!, amount: r.amount!);
  if (!context.mounted) return;
  if (confirmed.waitingDeposit) {
    await showVirtualAccount(context, confirmed);
  } else if (confirmed.done) {
    showSnack(context, '결제가 확인되었어요. 교환이 진행돼요.');
  } else {
    showSnack(context, confirmed.message ?? '결제 상태: ${confirmed.status}', error: true);
  }
}

/// 가상계좌 안내(은행 · 계좌번호 복사 · 기한)
Future<void> showVirtualAccount(BuildContext context, TossOrder o) => _sheet<void>(
      context,
      (c) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        _handle(),
        _title('가상계좌로 입금해 주세요', '입금이 확인되면(1분 안팎) 교환이 자동으로 진행돼요.'),
        Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(color: Sb.bg, borderRadius: BorderRadius.circular(16)),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(o.bank ?? '은행', style: TextStyle(fontSize: 14, color: Sb.text2)),
            const SizedBox(height: 4),
            Row(children: [
              Expanded(
                child: SelectableText(o.accountNumber ?? '-',
                    style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Sb.navy, letterSpacing: 0.5)),
              ),
              IconButton(
                tooltip: '계좌번호 복사',
                icon: const Icon(Icons.copy_rounded),
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: o.accountNumber ?? ''));
                  showSnack(c, '계좌번호를 복사했어요.');
                },
              ),
            ]),
            const SizedBox(height: 8),
            Text('입금액 ${formatWon(o.total)}', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: Sb.navy)),
            if (o.holder != null) Text('예금주 ${o.holder}', style: sbCaption),
            if (o.dueDate != null) Text('입금 기한 ${_due(o.dueDate!)}', style: sbCaption),
          ]),
        ),
        const SizedBox(height: 12),
        Text('테스트 결제예요. 토스페이먼츠 개발자센터 → 테스트 결제내역에서 이 주문의 ‘입금 처리’를 누르면 입금된 것으로 확인돼요.',
            style: TextStyle(fontSize: 13, height: 1.45, color: Sb.text3)),
        const SizedBox(height: 16),
        FilledButton(onPressed: () => Navigator.pop(c), child: const Text('확인')),
      ]),
    );

String _due(String iso) {
  final t = DateTime.tryParse(iso)?.toLocal();
  if (t == null) return iso;
  return '${t.month}월 ${t.day}일 ${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}까지';
}

// ───────────── ③ 운송장 입력 ─────────────

/// 내가 보낸 물건의 운송장 입력. 택배사 접수가 확인되면 바뀐 교환을 돌려준다
Future<Exchange?> showShipmentSheet(BuildContext context, AppServices services, Exchange e) async {
  List<Carrier> carriers;
  try {
    carriers = await services.exchanges.carriers();
  } on ApiException catch (err) {
    if (context.mounted) showSnack(context, err.message, error: true);
    return null;
  }
  if (!context.mounted || carriers.isEmpty) return null;
  return _sheet<Exchange>(context, (c) => _ShipmentForm(services: services, exchange: e, carriers: carriers));
}

class _ShipmentForm extends StatefulWidget {
  const _ShipmentForm({required this.services, required this.exchange, required this.carriers});

  final AppServices services;
  final Exchange exchange;
  final List<Carrier> carriers;

  @override
  State<_ShipmentForm> createState() => _ShipmentFormState();
}

class _ShipmentFormState extends State<_ShipmentForm> {
  final _invoice = TextEditingController();
  late String _carrier = widget.carriers.first.code;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _invoice.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) return;
    final no = _invoice.text.replaceAll(RegExp(r'[^0-9A-Za-z]'), '');
    if (no.length < 8) {
      setState(() => _error = '운송장 번호를 다시 입력해 주세요. (숫자 8자리 이상)');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final updated =
          await widget.services.exchanges.submitShipment(widget.exchange.id, carrierCode: _carrier, invoiceNo: no);
      if (mounted) Navigator.pop(context, updated);
    } on ApiException catch (err) {
      // 400 INVALID_INVOICE: "운송장 번호를 다시 입력해 주세요"(택배사에 접수되지 않은 번호)
      if (mounted) setState(() => _error = err.fieldError('invoiceNo') ?? err.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final deadline = widget.exchange.shipDeadline;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _handle(),
      _title('운송장 입력',
          '내가 보낸 물건의 택배사와 운송장 번호를 넣어 주세요. 택배사에 접수된 진짜 번호인지 확인해요.'
          '${deadline == null ? '' : '\n입력 마감: ${_deadline(deadline)}'}'),
      DropdownButtonFormField<String>(
        key: const ValueKey('ship-carrier'),
        initialValue: _carrier,
        isExpanded: true,
        decoration: sbInput('택배사'),
        items: [
          for (final c in widget.carriers) DropdownMenuItem(value: c.code, child: Text(c.name)),
        ],
        onChanged: (v) => setState(() => _carrier = v ?? _carrier),
      ),
      const SizedBox(height: 12),
      TextField(
        key: const ValueKey('ship-invoice'),
        controller: _invoice,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]'))],
        decoration: sbInput('운송장 번호', hint: _carrier == 'TEST' ? '테스트 택배: 숫자 10~14자리' : '숫자만(하이픈 가능)', errorText: _error),
        onSubmitted: (_) => _submit(),
      ),
      if (_carrier == 'TEST') ...[
        const SizedBox(height: 8),
        Text('시연용 테스트 택배는 등록 뒤 1분마다 단계가 올라가 약 4분 뒤 배송 완료가 돼요.', style: sbCaption),
      ],
      const SizedBox(height: 16),
      FilledButton(
        key: const ValueKey('ship-submit'),
        onPressed: _busy ? null : _submit,
        child: _busy
            ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator.adaptive(strokeWidth: 2.5))
            : const Text('운송장 확인하고 등록'),
      ),
    ]);
  }
}

String _deadline(DateTime t) {
  final l = t.toLocal();
  final days = l.difference(DateTime.now()).inHours / 24;
  final d = days <= 0 ? '오늘' : 'D-${days.ceil()}';
  return '${l.month}월 ${l.day}일 ${l.hour.toString().padLeft(2, '0')}:${l.minute.toString().padLeft(2, '0')} ($d)';
}

/// 마감 표시(카드에서도 씀)
String formatShipDeadline(DateTime t) => _deadline(t);

// ───────────── ④ 거래 후기 ─────────────

/// 별점 1~5 + 300자. 쓰면 true
Future<bool> showReviewSheet(BuildContext context, AppServices services, Exchange e, String otherNickname) async {
  final ok = await _sheet<bool>(context, (c) => _ReviewForm(services: services, exchange: e, otherNickname: otherNickname));
  return ok == true;
}

class _ReviewForm extends StatefulWidget {
  const _ReviewForm({required this.services, required this.exchange, required this.otherNickname});

  final AppServices services;
  final Exchange exchange;
  final String otherNickname;

  @override
  State<_ReviewForm> createState() => _ReviewFormState();
}

class _ReviewFormState extends State<_ReviewForm> {
  int _rating = 5;
  final _text = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      await widget.services.exchanges.writeReview(widget.exchange.id, rating: _rating, content: _text.text.trim());
      if (mounted) Navigator.pop(context, true);
    } on ApiException catch (err) {
      if (mounted) showSnack(context, err.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      _handle(),
      _title('${widget.otherNickname}님과의 거래는 어땠나요?', '후기는 ${widget.otherNickname}님 프로필에 보여요.'),
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        for (var i = 1; i <= 5; i++)
          IconButton(
            key: ValueKey('star-$i'),
            iconSize: 38,
            onPressed: () => setState(() => _rating = i),
            icon: Icon(i <= _rating ? Icons.star_rounded : Icons.star_outline_rounded, color: const Color(0xFFFFB800)),
          ),
      ]),
      const SizedBox(height: 8),
      TextField(
        key: const ValueKey('review-text'),
        controller: _text,
        maxLines: 4,
        maxLength: 300,
        decoration: sbInput('후기(선택)', hint: '포장 · 약속 · 물건 상태는 어땠나요?'),
      ),
      const SizedBox(height: 8),
      FilledButton(
        key: const ValueKey('review-submit'),
        onPressed: _busy ? null : _submit,
        child: const Text('후기 남기기'),
      ),
    ]);
  }
}
