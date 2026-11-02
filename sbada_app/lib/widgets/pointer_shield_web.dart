import 'package:flutter/widgets.dart';
import 'package:web/web.dart' as web;

/// 웹: child 크기만 한 빈 div 를 child 바로 뒤에 깐다. 클릭은 이 div(→ Flutter)가 받고, 아래 iframe 은 못 받는다.
class PointerShield extends StatelessWidget {
  const PointerShield({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Stack(fit: StackFit.passthrough, children: [
      Positioned.fill(
        child: HtmlElementView.fromTagName(
          tagName: 'div',
          isVisible: false,
          onElementCreated: (Object el) {
            final div = el as web.HTMLElement;
            div.style.width = '100%';
            div.style.height = '100%';
          },
        ),
      ),
      child,
    ]);
  }
}
