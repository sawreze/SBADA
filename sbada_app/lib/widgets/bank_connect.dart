import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/wallet.dart';
import '../repositories/wallet_repository.dart';
import '../theme/app_theme.dart';
import '../util/apple.dart';
import '../util/format.dart';
import 'common.dart';
import 'expand_route.dart';
import 'liquid_button.dart';

/// 결제할 때 '계좌 연결' (디자인 캔버스 BankConnect · AccountInput · AppSwitch, 2026-11-24). 모두 시연용.
///  1) 은행 고르기 시트(2열 카드: 아이콘 + 이름)  2) 계좌 직접 연결 화면(계좌번호 · 예금주 · 1원 인증 · 동의)
///  3) 간편결제(카카오페이 · 토스페이)는 그 회사 앱으로 넘어가는 화면을 거친다.

class BankInfo {
  const BankInfo(this.code, this.name, this.asset);

  final String code;
  final String name;
  final String asset;
}

const banks = [
  BankInfo('KAKAOBANK', '카카오뱅크', 'assets/banks/bank_kakaobank.png'),
  BankInfo('TOSSBANK', '토스뱅크', 'assets/banks/bank_tossbank.png'),
  BankInfo('KB', 'KB국민은행', 'assets/banks/bank_kb.png'),
  BankInfo('SHINHAN', '신한은행', 'assets/banks/bank_shinhan.png'),
  BankInfo('WOORI', '우리은행', 'assets/banks/bank_woori.png'),
  BankInfo('HANA', '하나은행', 'assets/banks/bank_hana.png'),
];

/// 계좌 연결 전체 흐름: 은행 시트 → 직접 연결 화면. 연결되면 등록된 계좌를 돌려준다.
Future<WalletAccount?> connectBankAccount(BuildContext context, WalletRepository repo) async {
  final bank = await showModalBottomSheet<BankInfo>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Sb.card,
    shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(28))),
    builder: (_) => const _BankPicker(),
  );
  if (bank == null || !context.mounted) return null;
  return Navigator.of(context)
      .push<WalletAccount>(appRoute(builder: (_) => AccountConnectScreen(repo: repo, bank: bank)));
}

class _BankPicker extends StatefulWidget {
  const _BankPicker();

  @override
  State<_BankPicker> createState() => _BankPickerState();
}

class _BankPickerState extends State<_BankPicker> {
  BankInfo _picked = banks.first;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(18, 12, 18, 24),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Center(
          child: Container(
              width: 40, height: 4, decoration: BoxDecoration(color: Sb.line, borderRadius: BorderRadius.circular(2))),
        ),
        const SizedBox(height: 16),
        Text('계좌 연결', style: TextStyle(fontSize: 24, fontWeight: FontWeight.w900, color: Sb.navy)),
        const SizedBox(height: 6),
        Text('연결할 은행을 선택해 주세요', style: TextStyle(fontSize: 14, color: Sb.text2)),
        const SizedBox(height: 18),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 10,
          crossAxisSpacing: 10,
          childAspectRatio: 1.6,
          children: [for (final b in banks) _card(b)],
        ),
        const SizedBox(height: 18),
        Row(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.info_outline_rounded, size: 16, color: Sb.text3),
          const SizedBox(width: 6),
          Text('시연용 화면이에요', style: TextStyle(fontSize: 13, color: Sb.text3)),
        ]),
        const SizedBox(height: 12),
        LiquidButton(
            key: const ValueKey('bank-next'),
            primary: true,
            label: '다음',
            onPressed: () => Navigator.of(context).pop(_picked)),
      ]),
    );
  }

  Widget _card(BankInfo b) {
    final on = b.code == _picked.code;
    return Semantics(
      button: true,
      selected: on,
      label: b.name,
      child: GestureDetector(
        key: ValueKey('bank-${b.code}'),
        onTap: () {
          Apple.tick();
          setState(() => _picked = b);
        },
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            color: on ? const Color(0xFFE6F7EE) : Sb.card,
            border: Border.all(color: on ? const Color(0xFF5FBF8E) : Sb.line, width: on ? 1.5 : 1),
            boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.05), blurRadius: 12, offset: const Offset(0, 4))],
          ),
          child: Stack(children: [
            Center(
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Image.asset(b.asset, height: 30),
                const SizedBox(height: 6),
                Text(b.name, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: Sb.navy)),
              ]),
            ),
            if (on)
              const Positioned(
                right: 8,
                top: 8,
                child: CircleAvatar(
                    radius: 11,
                    backgroundColor: Color(0xFF2F8A63),
                    child: Icon(Icons.check_rounded, size: 15, color: Colors.white)),
              ),
          ]),
        ),
      ),
    );
  }
}

/// 계좌 직접 연결: 계좌번호 · 예금주 · 1원 인증(시연: 숫자 4자리면 통과) · 동의 → 결제 서비스에 계좌 등록(암호화 저장)
class AccountConnectScreen extends StatefulWidget {
  const AccountConnectScreen({super.key, required this.repo, required this.bank});

  final WalletRepository repo;
  final BankInfo bank;

  @override
  State<AccountConnectScreen> createState() => _AccountConnectScreenState();
}

class _AccountConnectScreenState extends State<AccountConnectScreen> {
  final _number = TextEditingController();
  final _holder = TextEditingController();
  final _code = TextEditingController();
  bool _sent = false;
  bool _agree = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _number.dispose();
    _holder.dispose();
    _code.dispose();
    super.dispose();
  }

  bool get _ready =>
      _number.text.replaceAll(RegExp(r'[^0-9]'), '').length >= 10 &&
      _holder.text.trim().isNotEmpty &&
      _sent &&
      _code.text.length == 4 &&
      _agree;

  Future<void> _connect() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final acc = await widget.repo
          .addAccount(bankCode: widget.bank.code, accountNumber: _number.text.trim(), holder: _holder.text.trim());
      if (!mounted) return;
      showSnack(context, '${acc.display} 계좌를 연결했어요.');
      Navigator.of(context).pop(acc);
    } catch (e) {
      if (mounted) setState(() => _error = messageOf(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  InputDecoration _dec(String hint) => InputDecoration(hintText: hint);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('계좌 직접 연결')),
      body: ListView(padding: const EdgeInsets.fromLTRB(20, 8, 20, 24), children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
              color: Sb.card, borderRadius: BorderRadius.circular(18), border: Border.all(color: Sb.line)),
          child: Row(children: [
            Image.asset(widget.bank.asset, height: 26),
            const SizedBox(width: 10),
            Expanded(child: Text(widget.bank.name, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: Sb.navy))),
            TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('은행 바꾸기')),
          ]),
        ),
        const SizedBox(height: 16),
        Text('계좌번호', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Sb.text2)),
        const SizedBox(height: 6),
        TextField(
          key: const ValueKey('connect-number'),
          controller: _number,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]')), LengthLimitingTextInputFormatter(20)],
          onChanged: (_) => setState(() {}),
          decoration: _dec('숫자만 입력 (예: 3333012345678)'),
        ),
        const SizedBox(height: 14),
        Text('예금주', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: Sb.text2)),
        const SizedBox(height: 6),
        TextField(
          key: const ValueKey('connect-holder'),
          controller: _holder,
          onChanged: (_) => setState(() {}),
          inputFormatters: [LengthLimitingTextInputFormatter(30)],
          decoration: _dec('본인 이름'),
        ),
        const SizedBox(height: 4),
        Text('본인 명의 계좌만 연결할 수 있어요', style: TextStyle(fontSize: 12, color: Sb.text3)),
        const SizedBox(height: 16),
        Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
              color: Sb.card, borderRadius: BorderRadius.circular(18), border: Border.all(color: Sb.line)),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Text('1원 인증', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: Sb.navy)),
              const Spacer(),
              SizedBox(
                width: 120,
                child: LiquidButton(
                  key: const ValueKey('connect-send'),
                  height: 38,
                  fontSize: 13,
                  label: _sent ? '다시 보내기' : '1원 보내기',
                  onPressed: _number.text.replaceAll(RegExp(r'[^0-9]'), '').length >= 10
                      ? () => setState(() => _sent = true)
                      : null,
                ),
              ),
            ]),
            const SizedBox(height: 8),
            Text(
                _sent
                    ? '이 계좌로 1원을 보냈어요. 은행 앱의 입금자명 "쓰바다 ○○○○" 의 숫자 4자리를 입력해 주세요. (시연: 아무 숫자 4자리)'
                    : '계좌번호를 넣고 1원을 보내면 본인 계좌인지 확인할 수 있어요.',
                style: TextStyle(fontSize: 12.5, height: 1.5, color: Sb.text2)),
            if (_sent) ...[
              const SizedBox(height: 10),
              TextField(
                key: const ValueKey('connect-code'),
                controller: _code,
                keyboardType: TextInputType.number,
                textAlign: TextAlign.center,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(4)],
                onChanged: (_) => setState(() {}),
                style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800, letterSpacing: 8),
                decoration: _dec('0000'),
              ),
            ],
          ]),
        ),
        const SizedBox(height: 12),
        CheckboxListTile(
          key: const ValueKey('connect-agree'),
          value: _agree,
          onChanged: (v) => setState(() => _agree = v ?? false),
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          title: Text('출금 이체 · 개인정보 제공에 동의해요. 계좌번호는 암호화해서 보관해요.',
              style: TextStyle(fontSize: 13, color: Sb.text2)),
        ),
        if (_error != null) Text(_error!, style: TextStyle(color: Sb.red, fontSize: 13)),
        const SizedBox(height: 8),
        Text('시연용이라 실제 은행에 연결되지 않아요', textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: Sb.text3)),
        const SizedBox(height: 10),
        LiquidButton(
            key: const ValueKey('connect-submit'),
            primary: true,
            busy: _busy,
            label: '연결하고 결제로 돌아가기',
            onPressed: _ready ? _connect : null),
      ]),
    );
  }
}

/// 간편결제: 그 회사 앱으로 넘어가는 화면(시연). '앱 열기' → true, '웹에서 결제' → true(웹 결제로 간주), 뒤로 → null
class AppSwitchScreen extends StatelessWidget {
  const AppSwitchScreen({super.key, required this.appName, required this.total});

  final String appName;
  final int total;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 20),
          child: Column(children: [
            const SizedBox(height: 40),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              _appTile(const Text('sbada', style: TextStyle(fontWeight: FontWeight.w900, color: Color(0xFF0D1F16))),
                  Sb.blue),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 16),
                child: Icon(Icons.more_horiz_rounded, color: Color(0xFF2F8A63), size: 32),
              ),
              _appTile(Text(appName, style: TextStyle(fontWeight: FontWeight.w800, color: Sb.navy)), Sb.card),
            ]),
            const SizedBox(height: 32),
            Text('$appName 앱으로 이동하고 있어요',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900, color: Sb.navy)),
            const SizedBox(height: 10),
            Text('$appName 앱에서 ${formatWon(total)} 결제를 마치면\n자동으로 쓰바다로 돌아와요.',
                textAlign: TextAlign.center, style: TextStyle(fontSize: 14, height: 1.6, color: Sb.text2)),
            const Spacer(),
            Text('시연용이라 실제 결제는 일어나지 않아요', style: TextStyle(fontSize: 12, color: Sb.text3)),
            const SizedBox(height: 10),
            LiquidButton(
                key: const ValueKey('appswitch-open'),
                primary: true,
                label: '$appName 앱 열기',
                onPressed: () => Navigator.of(context).pop(true)),
            const SizedBox(height: 10),
            LiquidButton(
                height: 48,
                fontSize: 14,
                label: '$appName 앱이 없어요 · 웹에서 결제',
                onPressed: () => Navigator.of(context).pop(true)),
          ]),
        ),
      ),
    );
  }

  Widget _appTile(Widget child, Color color) => Container(
        width: 72,
        height: 72,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: Sb.line),
          boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.06), blurRadius: 14, offset: const Offset(0, 6))],
        ),
        child: child,
      );
}
