import 'package:flutter/widgets.dart';

/// 앱에서는 덮개가 필요 없다(WebView 위 Flutter 위젯은 원래 눌린다).
class PointerShield extends StatelessWidget {
  const PointerShield({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => child;
}
