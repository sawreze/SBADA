/// 128000 → "128,000원"
String formatWon(int value) => '${formatNumber(value)}원';

String formatNumber(int value) {
  final negative = value < 0;
  final digits = value.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < digits.length; i++) {
    final fromEnd = digits.length - i;
    buf.write(digits[i]);
    if (fromEnd > 1 && fromEnd % 3 == 1) buf.write(',');
  }
  return negative ? '-$buf' : buf.toString();
}

/// 12000 → "1.2만원", 280000 → "28만원"
String formatShortWon(int value) {
  if (value >= 10000) {
    final man = value / 10000;
    final text = man == man.roundToDouble() ? man.toStringAsFixed(0) : man.toStringAsFixed(1);
    return '$text만원';
  }
  return formatWon(value);
}

/// +8% / -12%
String formatPercent(int value) => value > 0 ? '+$value%' : '$value%';

String timeAgo(DateTime time, {DateTime? now}) {
  final diff = (now ?? DateTime.now()).difference(time);
  if (diff.inMinutes < 1) return '방금 전';
  if (diff.inHours < 1) return '${diff.inMinutes}분 전';
  if (diff.inDays < 1) return '${diff.inHours}시간 전';
  if (diff.inDays < 7) return '${diff.inDays}일 전';
  return '${time.month}월 ${time.day}일';
}

DateTime parseTime(Object? raw) {
  if (raw is String) {
    return DateTime.tryParse(raw)?.toLocal() ?? DateTime.now();
  }
  return DateTime.now();
}
