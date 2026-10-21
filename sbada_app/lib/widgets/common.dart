import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../theme/app_theme.dart';

/// 빈 목록·오류·로딩을 서로 다른 화면 상태로 보여 준다(10장: '한 건도 없음'과 '연결할 수 없음'은 다르다).
class EmptyView extends StatelessWidget {
  const EmptyView({super.key, required this.emoji, required this.title, this.message, this.action});

  final String emoji;
  final String title;
  final String? message;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(emoji, style: const TextStyle(fontSize: 56)),
          const SizedBox(height: 16),
          Text(title, style: sbH2, textAlign: TextAlign.center),
          if (message != null) ...[
            const SizedBox(height: 8),
            Text(message!, style: sbBody, textAlign: TextAlign.center),
          ],
          if (action != null) ...[const SizedBox(height: 20), action!],
        ]),
      ),
    );
  }
}

class ErrorView extends StatelessWidget {
  const ErrorView({super.key, required this.error, required this.onRetry});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final e = error;
    final network = e is ApiException && e.isNetwork;
    return EmptyView(
      emoji: network ? '📡' : '😵',
      title: network ? '서버에 연결할 수 없어요' : '불러오지 못했어요',
      message: messageOf(e),
      action: OutlinedButton.icon(
        onPressed: onRetry,
        icon: const Icon(Icons.refresh),
        label: const Text('다시 불러오기'),
      ),
    );
  }
}

class LoadingView extends StatelessWidget {
  const LoadingView({super.key, this.message});

  final String? message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        const CircularProgressIndicator(),
        if (message != null) ...[const SizedBox(height: 16), Text(message!, style: sbBody)],
      ]),
    );
  }
}

String messageOf(Object error) {
  if (error is ApiException) return error.message;
  if (error is FormatException) return '서버 응답 형식이 예상과 달라요. (${error.message})';
  return '알 수 없는 오류가 났어요.';
}

void showSnack(BuildContext context, String message, {bool error = false}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      content: Text(message),
      behavior: SnackBarBehavior.floating,
      backgroundColor: error ? Sb.red : Sb.chip,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
    ));
}

/// 작은 회색 알약 모양 표시
class Pill extends StatelessWidget {
  const Pill(this.text, {super.key, this.color, this.textColor, this.icon, this.bold = false});

  final String text;
  /// 비워 두면 모드에 맞는 기본 채움색(Sb.bg) · 글자색(Sb.text2)
  final Color? color;
  final Color? textColor;
  final IconData? icon;
  /// 강조 칩(민트 칩 등)은 글자를 더 굵게
  final bool bold;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: color ?? Sb.bg, borderRadius: BorderRadius.circular(999)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) ...[Icon(icon, size: 14, color: textColor ?? Sb.text2), const SizedBox(width: 4)],
        Flexible(
          child: Text(text,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                  fontSize: 12, fontWeight: bold ? FontWeight.w700 : FontWeight.w600, color: textColor ?? Sb.text2)),
        ),
      ]),
    );
  }
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key, this.trailing});

  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 10),
      child: Row(children: [
        Expanded(child: Text(text, style: sbH2)),
        ?trailing,
      ]),
    );
  }
}
