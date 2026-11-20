import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/wallet.dart';
import '../repositories/wallet_repository.dart';
import '../theme/app_theme.dart';
import '../util/apple.dart';
import '../util/format.dart';
import 'common.dart';

/// 지갑 화면 · 채팅방에서 여는 아래쪽 시트들(계좌 추가 · 출금 · 충전 · 추가 요금 결제). 모두 시연용.

Future<T?> _sheet<T>(BuildContext context, Widget child) => showModalBottomSheet<T>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Sb.card,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
      builder: (context) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
          child: child,
        ),
      ),
    );

/// 결과 알림창: "출금되었습니다" 같은 제목 + 내용
Future<void> showWalletDone(BuildContext context, String title, String body) {
  Apple.tap();
  return showDialog<void>(
    context: context,
    builder: (context) => AlertDialog.adaptive(
      title: Text(title),
      content: Text(body),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('확인')),
      ],
    ),
  );
}

class _Handle extends StatelessWidget {
  const _Handle(this.title, this.subtitle);

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Center(
        child: Container(
          width: 40,
          height: 4,
          margin: const EdgeInsets.only(bottom: 16),
          decoration: BoxDecoration(color: Sb.line, borderRadius: BorderRadius.circular(2)),
        ),
      ),
      Text(title, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Sb.navy)),
      if (subtitle != null) ...[
        const SizedBox(height: 4),
        Text(subtitle!, style: TextStyle(fontSize: 13, color: Sb.text2)),
      ],
      const SizedBox(height: 16),
    ]);
  }
}

/// 시연 표시 띠
class DemoBanner extends StatelessWidget {
  const DemoBanner({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(color: Sb.blueSoft, borderRadius: BorderRadius.circular(12)),
      child: Row(children: [
        Icon(Icons.info_outline_rounded, size: 16, color: Sb.mintText),
        const SizedBox(width: 6),
        Expanded(
          child: Text('시연용이에요. 실제 결제 · 송금은 일어나지 않아요.',
              style: TextStyle(fontSize: 12.5, color: Sb.mintText, fontWeight: FontWeight.w600)),
        ),
      ]),
    );
  }
}

Widget _chips(List<WalletOption> options, String? selected, ValueChanged<String> onSelect) => Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final o in options)
          ChoiceChip(
            label: Text(o.label),
            selected: o.code == selected,
            onSelected: (_) => onSelect(o.code),
            selectedColor: Sb.blue,
            labelStyle: TextStyle(
              color: o.code == selected ? Sb.onAccent : Sb.navy,
              fontWeight: o.code == selected ? FontWeight.w700 : FontWeight.w500,
            ),
          ),
      ],
    );

Widget _label(String text) => Padding(
      padding: const EdgeInsets.only(top: 4, bottom: 8),
      child: Text(text, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Sb.text2)),
    );

class _AmountField extends StatelessWidget {
  const _AmountField({required this.controller, required this.onChanged, this.max});

  final TextEditingController controller;
  final ValueChanged<int> onChanged;
  final int? max;

  int get _value => int.tryParse(controller.text) ?? 0;

  void _add(int v) {
    var next = _value + v;
    if (max != null && next > max!) next = max!;
    controller.text = '$next';
    onChanged(next);
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      TextField(
        key: const ValueKey('wallet-amount'),
        controller: controller,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(7)],
        onChanged: (_) => onChanged(_value),
        style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800, color: Sb.navy),
        decoration: const InputDecoration(hintText: '금액 입력', suffixText: '원'),
      ),
      const SizedBox(height: 8),
      Wrap(spacing: 8, children: [
        for (final v in const [10000, 50000, 100000])
          ActionChip(label: Text('+${formatWon(v)}'), onPressed: () => _add(v)),
        if (max != null)
          ActionChip(
            label: const Text('전액'),
            onPressed: () {
              controller.text = '${max!}';
              onChanged(max!);
            },
          ),
      ]),
    ]);
  }
}

Widget _primary(String text, {required bool busy, required VoidCallback? onPressed, Key? key}) => SizedBox(
      width: double.infinity,
      height: 52,
      child: FilledButton(
        key: key,
        onPressed: busy ? null : onPressed,
        style: FilledButton.styleFrom(
          backgroundColor: Sb.blue,
          foregroundColor: Sb.onAccent,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ),
        child: busy
            ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2.4))
            : Text(text, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
      ),
    );

// ───────────────────────── 계좌 추가 ─────────────────────────

/// 계좌 추가. 성공하면 등록된 계좌를 돌려준다
Future<WalletAccount?> showAddAccountSheet(BuildContext context, WalletRepository repo, List<WalletOption> banks) =>
    _sheet<WalletAccount>(context, _AddAccount(repo: repo, banks: banks));

class _AddAccount extends StatefulWidget {
  const _AddAccount({required this.repo, required this.banks});

  final WalletRepository repo;
  final List<WalletOption> banks;

  @override
  State<_AddAccount> createState() => _AddAccountState();
}

class _AddAccountState extends State<_AddAccount> {
  final _number = TextEditingController();
  final _holder = TextEditingController();
  String? _bank;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.banks.isNotEmpty) _bank = widget.banks.first.code;
  }

  @override
  void dispose() {
    _number.dispose();
    _holder.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final bank = _bank;
    if (bank == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final acc = await widget.repo.addAccount(
          bankCode: bank, accountNumber: _number.text.trim(), holder: _holder.text.trim());
      if (mounted) Navigator.of(context).pop(acc);
    } catch (e) {
      if (mounted) setState(() => _error = messageOf(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      const _Handle('출금 계좌 추가', '계좌번호는 서버에서 RSA + AES 로 암호화해 보관해요.'),
      _label('은행'),
      _chips(widget.banks, _bank, (c) => setState(() => _bank = c)),
      const SizedBox(height: 16),
      _label('계좌번호'),
      TextField(
        key: const ValueKey('wallet-account-number'),
        controller: _number,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]')), LengthLimitingTextInputFormatter(20)],
        decoration: const InputDecoration(hintText: '숫자만 입력 (예: 3333012345678)'),
      ),
      const SizedBox(height: 16),
      _label('예금주'),
      TextField(
        key: const ValueKey('wallet-account-holder'),
        controller: _holder,
        inputFormatters: [LengthLimitingTextInputFormatter(30)],
        decoration: const InputDecoration(hintText: '이름'),
      ),
      if (_error != null) ...[
        const SizedBox(height: 12),
        Text(_error!, style: TextStyle(color: Sb.red, fontSize: 13)),
      ],
      const SizedBox(height: 20),
      _primary('등록하기', busy: _busy, onPressed: _save, key: const ValueKey('wallet-account-save')),
    ]);
  }
}

// ───────────────────────── 출금 ─────────────────────────

/// 출금. 성공하면 결과를 돌려주고 "출금되었습니다" 창을 띄운다
Future<WalletResult?> showWithdrawSheet(BuildContext context, WalletRepository repo, Wallet wallet) async {
  final result = await _sheet<WalletResult>(context, _Withdraw(repo: repo, wallet: wallet));
  if (result != null && context.mounted) {
    final memo = result.transaction.memo ?? '';
    await showWalletDone(context, result.message,
        '$memo 계좌로 ${formatWon(-result.transaction.amount)}을 보냈어요.\n남은 잔액 ${formatWon(result.balance)}\n\n시연용이라 실제 송금은 되지 않아요.');
  }
  return result;
}

class _Withdraw extends StatefulWidget {
  const _Withdraw({required this.repo, required this.wallet});

  final WalletRepository repo;
  final Wallet wallet;

  @override
  State<_Withdraw> createState() => _WithdrawState();
}

class _WithdrawState extends State<_Withdraw> {
  final _amount = TextEditingController();
  int? _accountId;
  int _value = 0;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.wallet.accounts.isNotEmpty) _accountId = widget.wallet.accounts.first.id;
  }

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final id = _accountId;
    if (id == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = await widget.repo.withdraw(accountId: id, amount: _value);
      if (mounted) Navigator.of(context).pop(r);
    } catch (e) {
      if (mounted) setState(() => _error = messageOf(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final w = widget.wallet;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _Handle('출금하기', '출금 가능 ${formatWon(w.balance)}'),
      _label('받을 계좌'),
      if (w.accounts.isEmpty)
        Text('먼저 지갑 화면에서 계좌를 추가해 주세요.', style: TextStyle(color: Sb.text2))
      else
        _chips([for (final a in w.accounts) WalletOption('${a.id}', a.display)], _accountId?.toString(),
            (c) => setState(() => _accountId = int.parse(c))),
      const SizedBox(height: 16),
      _label('출금 금액'),
      _AmountField(controller: _amount, max: w.balance, onChanged: (v) => setState(() => _value = v)),
      if (_error != null) ...[
        const SizedBox(height: 12),
        Text(_error!, style: TextStyle(color: Sb.red, fontSize: 13)),
      ],
      const SizedBox(height: 20),
      _primary(_value > 0 ? '${formatWon(_value)} 출금하기' : '출금하기',
          busy: _busy,
          onPressed: _accountId == null || _value <= 0 ? null : _submit,
          key: const ValueKey('wallet-withdraw-submit')),
    ]);
  }
}

// ───────────────────────── 충전 ─────────────────────────

Future<WalletResult?> showChargeSheet(BuildContext context, WalletRepository repo, Wallet wallet) async {
  final result = await _sheet<WalletResult>(context, _Pay(repo: repo, wallet: wallet));
  if (result != null && context.mounted) {
    await showWalletDone(context, result.message,
        '${result.transaction.methodLabel ?? ''}로 ${formatWon(result.transaction.amount)} 충전했어요.\n잔액 ${formatWon(result.balance)}\n\n시연용이라 실제 결제는 되지 않아요.');
  }
  return result;
}

// ───────────────────────── 교환 추가 요금 ─────────────────────────

/// 채팅방에서: 상대가 요구한 추가 요금을 금액 + 수수료로 결제 → 상대 지갑에 금액이 들어간다
Future<WalletResult?> showPayExtraSheet(BuildContext context, WalletRepository repo,
    {required int toUserId, required String toNickname}) async {
  final result = await _sheet<WalletResult>(
      context, _Pay(repo: repo, toUserId: toUserId, toNickname: toNickname));
  if (result != null && context.mounted) {
    final t = result.transaction;
    await showWalletDone(context, result.message,
        '${t.methodLabel ?? ''}로 ${formatWon(-t.amount)} 결제했어요. (수수료 ${formatWon(t.fee)} 포함)\n$toNickname 님 지갑으로 ${formatWon(-t.amount - t.fee)}이 들어갔어요.\n\n시연용이라 실제 결제는 되지 않아요.');
  }
  return result;
}

/// 충전(toUserId 없음) · 추가 요금 결제(toUserId 있음) 공용 화면: 결제수단 고르기 + 금액
class _Pay extends StatefulWidget {
  const _Pay({required this.repo, this.wallet, this.toUserId, this.toNickname});

  final WalletRepository repo;
  final Wallet? wallet;
  final int? toUserId;
  final String? toNickname;

  @override
  State<_Pay> createState() => _PayState();
}

class _PayState extends State<_Pay> {
  final _amount = TextEditingController();
  Wallet? _wallet;
  String? _method;
  int _value = 0;
  bool _busy = false;
  String? _error;

  bool get _extra => widget.toUserId != null;

  @override
  void initState() {
    super.initState();
    _wallet = widget.wallet;
    if (_wallet == null) {
      widget.repo.wallet().then((w) {
        if (mounted) setState(() => _wallet = w);
      }, onError: (Object e) {
        if (mounted) setState(() => _error = messageOf(e));
      });
    }
  }

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final method = _method;
    if (method == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = _extra
          ? await widget.repo.payExtra(toUserId: widget.toUserId!, amount: _value, method: method)
          : await widget.repo.charge(method: method, amount: _value);
      if (mounted) Navigator.of(context).pop(r);
    } catch (e) {
      if (mounted) setState(() => _error = messageOf(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final w = _wallet;
    final fee = w == null || !_extra ? 0 : walletFee(_value, w.feePercent);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      _Handle(_extra ? '추가 요금 보내기' : '충전하기',
          _extra ? '${widget.toNickname} 님에게 · 앱이 받아 두었다가 상대 지갑으로 넣어 줘요' : '결제창 흉내 · 지갑 잔액이 늘어나요'),
      const DemoBanner(),
      const SizedBox(height: 16),
      if (w == null && _error == null)
        const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
      else if (w != null) ...[
        _label('결제수단'),
        _chips(w.methods, _method, (c) => setState(() => _method = c)),
        const SizedBox(height: 16),
        _label(_extra ? '상대가 요구한 금액' : '충전 금액'),
        _AmountField(controller: _amount, onChanged: (v) => setState(() => _value = v)),
        if (_extra && _value > 0) ...[
          const SizedBox(height: 14),
          _row('보낼 금액', formatWon(_value)),
          _row('수수료 ${w.feePercent}%', formatWon(fee)),
          const Divider(height: 20),
          _row('결제할 금액', formatWon(_value + fee), bold: true),
        ],
      ],
      if (_error != null) ...[
        const SizedBox(height: 12),
        Text(_error!, style: TextStyle(color: Sb.red, fontSize: 13)),
      ],
      const SizedBox(height: 20),
      _primary(
        _value > 0 ? '${formatWon(_value + fee)} 결제하기' : '결제하기',
        busy: _busy,
        onPressed: _method == null || _value <= 0 || w == null ? null : _submit,
        key: const ValueKey('wallet-pay-submit'),
      ),
    ]);
  }

  Widget _row(String k, String v, {bool bold = false}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(children: [
          Text(k, style: TextStyle(color: bold ? Sb.navy : Sb.text2, fontWeight: bold ? FontWeight.w800 : FontWeight.w500)),
          const Spacer(),
          Text(v, style: TextStyle(color: Sb.navy, fontWeight: bold ? FontWeight.w800 : FontWeight.w600, fontSize: bold ? 17 : 14)),
        ]),
      );
}
