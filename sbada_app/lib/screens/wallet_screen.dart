import 'package:flutter/material.dart';

import '../models/wallet.dart';
import '../repositories/wallet_repository.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/settings_group.dart';
import '../widgets/wallet_sheets.dart';

/// 지갑(시연용) — 잔액 · 출금 · 충전 · 출금 계좌 · 거래 내역.
/// 데이터는 결제 마이크로서비스(sbada-payment)에 있고, 앱은 메인 서버 게이트웨이를 거쳐 받는다.
class WalletScreen extends StatefulWidget {
  const WalletScreen({super.key, required this.services, this.repository});

  final AppServices services;

  /// 테스트에서 가짜 저장소를 넣을 때
  final WalletRepository? repository;

  @override
  State<WalletScreen> createState() => _WalletScreenState();
}

class _WalletScreenState extends State<WalletScreen> {
  late final WalletRepository _repo = widget.repository ?? WalletRepository(widget.services.api);
  Wallet? _wallet;
  Object? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = _wallet == null;
      _error = null;
    });
    try {
      final w = await _repo.wallet();
      if (mounted) setState(() => _wallet = w);
    } catch (e) {
      if (mounted) setState(() => _error = e);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _withdraw(Wallet w) async {
    if (w.accounts.isEmpty) {
      final added = await showAddAccountSheet(context, _repo, w.banks);
      if (added == null) return;
      await _load();
      if (!mounted || _wallet == null) return;
      w = _wallet!;
    }
    final r = await showWithdrawSheet(context, _repo, w);
    if (r != null) await _load();
  }

  Future<void> _charge(Wallet w) async {
    final r = await showChargeSheet(context, _repo, w);
    if (r != null) await _load();
  }

  Future<void> _addAccount(Wallet w) async {
    final added = await showAddAccountSheet(context, _repo, w.banks);
    if (added != null) {
      if (mounted) showSnack(context, '${added.display} 계좌를 등록했어요.');
      await _load();
    }
  }

  Future<void> _deleteAccount(WalletAccount a) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog.adaptive(
        title: const Text('계좌 삭제'),
        content: Text('${a.display} 계좌를 지울까요?'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('취소')),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('삭제')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await _repo.deleteAccount(a.id);
      await _load();
    } catch (e) {
      if (mounted) showSnack(context, messageOf(e), error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final w = _wallet;
    return Scaffold(
      backgroundColor: Sb.groupedBg,
      appBar: AppBar(title: const Text('지갑'), backgroundColor: Sb.groupedBg),
      body: _loading
          ? const LoadingView()
          : w == null
              ? ErrorView(error: _error ?? '불러오지 못했어요.', onRetry: _load)
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(padding: const EdgeInsets.only(bottom: 32), children: [
                    const Padding(padding: EdgeInsets.fromLTRB(16, 4, 16, 12), child: DemoBanner()),
                    _balanceCard(w),
                    SettingsGroup(title: '출금 계좌', children: [
                      for (final a in w.accounts)
                        SettingsRow(
                          icon: Icons.account_balance_outlined,
                          label: a.display,
                          subtitle: '예금주 ${a.holder}',
                          showChevron: false,
                          trailing: IconButton(
                            tooltip: '삭제',
                            icon: Icon(Icons.delete_outline_rounded, color: Sb.text3),
                            onPressed: () => _deleteAccount(a),
                          ),
                        ),
                      SettingsRow(
                        key: const ValueKey('wallet-add-account'),
                        icon: Icons.add_rounded,
                        label: '계좌 추가',
                        subtitle: '카카오뱅크 · 토스뱅크 · 시중은행',
                        onTap: () => _addAccount(w),
                      ),
                    ]),
                    SettingsGroup(title: '거래 내역', children: [
                      if (w.transactions.isEmpty)
                        Padding(
                          padding: const EdgeInsets.all(16),
                          child: Text('아직 거래가 없어요.', style: TextStyle(color: Sb.text2)),
                        ),
                      for (final t in w.transactions) _txRow(t),
                    ]),
                  ]),
                ),
    );
  }

  Widget _balanceCard(Wallet w) {
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(color: Sb.groupCard, borderRadius: BorderRadius.circular(Sb.radius)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('스바다 지갑 잔액', style: TextStyle(fontSize: 13, color: Sb.text2, fontWeight: FontWeight.w600)),
        const SizedBox(height: 6),
        Text(formatWon(w.balance),
            key: const ValueKey('wallet-balance'),
            style: TextStyle(fontSize: 30, fontWeight: FontWeight.w900, color: Sb.navy)),
        const SizedBox(height: 4),
        Text('교환 추가 요금은 보내는 사람이 수수료 ${w.feePercent}%를 더 내요.',
            style: TextStyle(fontSize: 12, color: Sb.text3)),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(
            child: FilledButton(
              key: const ValueKey('wallet-withdraw'),
              onPressed: () => _withdraw(w),
              style: FilledButton.styleFrom(
                backgroundColor: Sb.blue,
                foregroundColor: Sb.onAccent,
                minimumSize: const Size.fromHeight(48),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: const Text('출금', style: TextStyle(fontWeight: FontWeight.w800)),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: OutlinedButton(
              key: const ValueKey('wallet-charge'),
              onPressed: () => _charge(w),
              style: OutlinedButton.styleFrom(
                foregroundColor: Sb.navy,
                minimumSize: const Size.fromHeight(48),
                side: BorderSide(color: Sb.line),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: const Text('충전', style: TextStyle(fontWeight: FontWeight.w800)),
            ),
          ),
        ]),
      ]),
    );
  }

  Widget _txRow(WalletTx t) {
    final sign = t.amount > 0 ? '+' : '−';
    final detail = [
      if (t.methodLabel != null) t.methodLabel!,
      if (t.fee > 0) '수수료 ${formatWon(t.fee)}',
      if (t.memo != null && t.memo!.isNotEmpty) t.memo!,
    ].join(' · ');
    final when = '${t.createdAt.month}/${t.createdAt.day} '
        '${t.createdAt.hour.toString().padLeft(2, '0')}:${t.createdAt.minute.toString().padLeft(2, '0')}';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(children: [
        Icon(_icon(t.type), color: t.incoming ? Sb.green : Sb.text2),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(t.typeLabel, style: TextStyle(fontWeight: FontWeight.w700, color: Sb.navy)),
            Text(detail.isEmpty ? when : '$when · $detail',
                maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 12, color: Sb.text3)),
          ]),
        ),
        const SizedBox(width: 8),
        Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
          Text('$sign${formatWon(t.amount.abs())}',
              style: TextStyle(fontWeight: FontWeight.w800, color: t.incoming ? Sb.green : Sb.navy)),
          Text('잔액 ${formatWon(t.balanceAfter)}', style: TextStyle(fontSize: 11.5, color: Sb.text3)),
        ]),
      ]),
    );
  }

  static IconData _icon(String type) => switch (type) {
        'WITHDRAW' => Icons.north_east_rounded,
        'PAY' => Icons.credit_card_rounded,
        'RECEIVE' => Icons.south_west_rounded,
        'CHARGE' => Icons.add_card_rounded,
        _ => Icons.card_giftcard_rounded,
      };
}
