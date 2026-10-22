import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/models/app_notification.dart';
import 'package:sbada/models/item.dart';
import 'package:sbada/theme/app_theme.dart';
import 'package:sbada/widgets/ai_summary_card.dart';
import 'package:sbada/widgets/common.dart';

Widget _host(Widget child) => MaterialApp(theme: buildTheme(), home: Scaffold(body: child));

void main() {
  testWidgets('가격 표시: "12,000원" 뒤에 작은 글씨 "AI 분석 가치"(앞의 AI 는 없음)', (tester) async {
    await tester.pumpWidget(_host(const PriceText(12000)));
    final text = tester.widget<RichText>(find.byType(RichText).first).text.toPlainText();
    expect(text, '12,000원 AI 분석 가치');
    expect(text.startsWith('AI'), isFalse);
  });

  testWidgets('AI 상품 요약: 기다리는 동안 안내 → 요약 문장, 실패하면 칸을 숨긴다', (tester) async {
    final done = Completer<AiItemSummary>();
    await tester.pumpWidget(_host(AiSummaryCard(load: () => done.future)));
    expect(find.textContaining('요약하고 있어요'), findsOneWidget);

    done.complete(const AiItemSummary(summary: '작은 블루투스 스피커예요. 소리가 맑아요.', provider: 'google'));
    await tester.pump();
    expect(find.text('AI 상품 요약'), findsOneWidget);
    expect(find.text('작은 블루투스 스피커예요. 소리가 맑아요.'), findsOneWidget);

    await tester.pumpWidget(_host(AiSummaryCard(key: const ValueKey('fail'), load: () => Future.error('네트워크'))));
    await tester.pump();
    expect(find.byKey(const ValueKey('ai-summary')), findsNothing);
  });

  test('알림 설정: 찜 · 채팅 · 교환 스위치가 알림 종류와 맞게 연결되고, 설정에 없는 종류는 항상 받는다', () {
    const off = NotificationSettings(like: false, chat: false, exchange: false);
    for (final t in ['ITEM_LIKED', 'CHAT_MESSAGE', 'EXCHANGE_REQUESTED', 'EXCHANGE_ACCEPTED', 'EXCHANGE_CANCELED']) {
      expect(off.allows(t), isFalse, reason: t);
      expect(const NotificationSettings().allows(t), isTrue, reason: t);
    }
    expect(off.allows('MATCH_SUGGESTED'), isTrue);
    expect(off.allows('ITEM_HELD'), isTrue);
    final parsed = NotificationSettings.fromJson({'like': true, 'chat': false, 'exchange': true, 'pushReady': true});
    expect([parsed.like, parsed.chat, parsed.exchange, parsed.pushReady], [true, false, true, true]);
  });
}
