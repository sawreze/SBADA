import 'json.dart';

/// 결제 서비스(별도 마이크로서비스) 응답 모델 — 시연용, 실제 돈은 움직이지 않는다.
class WalletOption {
  const WalletOption(this.code, this.label);

  final String code;
  final String label;

  factory WalletOption.fromJson(Map<String, dynamic> j) => WalletOption(reqStr(j, 'code'), reqStr(j, 'label'));
}

class WalletAccount {
  const WalletAccount({
    required this.id,
    required this.bankCode,
    required this.bankName,
    required this.accountMasked,
    required this.holder,
  });

  final int id;
  final String bankCode;
  final String bankName;

  /// 뒷자리만(****1234). 전체 번호는 서버에서도 암호화돼 있다
  final String accountMasked;
  final String holder;

  String get display => '$bankName $accountMasked';

  factory WalletAccount.fromJson(Map<String, dynamic> j) => WalletAccount(
        id: reqInt(j, 'id'),
        bankCode: reqStr(j, 'bankCode'),
        bankName: reqStr(j, 'bankName'),
        accountMasked: reqStr(j, 'accountMasked'),
        holder: reqStr(j, 'holder'),
      );
}

class WalletTx {
  const WalletTx({
    required this.id,
    required this.type,
    required this.typeLabel,
    required this.amount,
    required this.fee,
    required this.balanceAfter,
    this.methodLabel,
    this.memo,
    required this.createdAt,
  });

  final int id;

  /// DEMO_CHARGE · CHARGE · WITHDRAW · PAY · RECEIVE
  final String type;
  final String typeLabel;

  /// 지갑에 들어오면 +, 나가면 −(PAY 는 카드 · 간편결제로 낸 총액)
  final int amount;
  final int fee;
  final int balanceAfter;
  final String? methodLabel;
  final String? memo;
  final DateTime createdAt;

  bool get incoming => amount > 0;

  factory WalletTx.fromJson(Map<String, dynamic> j) => WalletTx(
        id: reqInt(j, 'id'),
        type: reqStr(j, 'type'),
        typeLabel: reqStr(j, 'typeLabel'),
        amount: reqInt(j, 'amount'),
        fee: optInt(j, 'fee') ?? 0,
        balanceAfter: reqInt(j, 'balanceAfter'),
        methodLabel: optStr(j, 'methodLabel'),
        memo: optStr(j, 'memo'),
        createdAt: DateTime.tryParse(optStr(j, 'createdAt') ?? '')?.toLocal() ?? DateTime.now(),
      );
}

class Wallet {
  const Wallet({
    required this.demo,
    required this.balance,
    required this.feePercent,
    required this.minAmount,
    required this.maxAmount,
    required this.accounts,
    required this.transactions,
    required this.banks,
    required this.methods,
  });

  final bool demo;
  final int balance;
  final int feePercent;
  final int minAmount;
  final int maxAmount;
  final List<WalletAccount> accounts;
  final List<WalletTx> transactions;
  final List<WalletOption> banks;
  final List<WalletOption> methods;

  factory Wallet.fromJson(Map<String, dynamic> j) => Wallet(
        demo: optBool(j, 'demo'),
        balance: reqInt(j, 'balance'),
        feePercent: optInt(j, 'feePercent') ?? 5,
        minAmount: optInt(j, 'minAmount') ?? 1000,
        maxAmount: optInt(j, 'maxAmount') ?? 1000000,
        accounts: objList(j, 'accounts', WalletAccount.fromJson),
        transactions: objList(j, 'transactions', WalletTx.fromJson),
        banks: objList(j, 'banks', WalletOption.fromJson),
        methods: objList(j, 'methods', WalletOption.fromJson),
      );
}

/// 결제 · 출금 · 충전 결과: 알림 문구 + 바뀐 잔액
class WalletResult {
  const WalletResult({required this.message, required this.balance, required this.transaction});

  final String message;
  final int balance;
  final WalletTx transaction;

  factory WalletResult.fromJson(Map<String, dynamic> j) => WalletResult(
        message: reqStr(j, 'message'),
        balance: reqInt(j, 'balance'),
        transaction: WalletTx.fromJson(j['transaction'] as Map<String, dynamic>),
      );
}

/// 수수료 계산(서버와 같은 규칙: 금액 × 수수료율, 원 단위 반올림)
int walletFee(int amount, int feePercent) => (amount * feePercent / 100).round();
