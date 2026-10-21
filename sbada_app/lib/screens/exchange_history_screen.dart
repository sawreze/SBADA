import 'package:flutter/material.dart';

import '../models/exchange.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/exchange_card.dart';
import 'chat_room_screen.dart';

/// 교환 내역: 끝난 교환(성사 · 거절 · 취소)을 받은 것 · 보낸 것 구분 없이 최근 순으로.
/// 새 API 없이 기존 GET /api/exchanges?box=received · sent 를 합친다(대기 중 REQUESTED 는 받은/보낸 요청 메뉴에 있음).
class ExchangeHistoryScreen extends StatefulWidget {
  const ExchangeHistoryScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<ExchangeHistoryScreen> createState() => _ExchangeHistoryScreenState();
}

class _ExchangeHistoryScreenState extends State<ExchangeHistoryScreen> {
  late Future<List<Exchange>> _future;

  /// 칩 필터: 0 전체 · 1 성사 · 2 거절/취소
  int _filter = 0;
  static const _filters = ['전체', '교환 성사', '거절 · 취소'];

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    final ex = widget.services.exchanges;
    _future = Future.wait([ex.list(received: true), ex.list(received: false)]).then((r) {
      final all = [...r[0], ...r[1]].where((e) => e.status != 'REQUESTED').toList()
        ..sort((a, b) => b.createdAt.compareTo(a.createdAt));
      return all;
    });
  }

  List<Exchange> _apply(List<Exchange> list) => switch (_filter) {
        1 => list.where((e) => e.status == 'ACCEPTED').toList(),
        2 => list.where((e) => e.status == 'REJECTED' || e.status == 'CANCELED').toList(),
        _ => list,
      };

  @override
  Widget build(BuildContext context) {
    final s = widget.services;
    return Scaffold(
      appBar: AppBar(title: const Text('교환 내역', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
      body: Column(children: [
        SizedBox(
          height: 50,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            children: [
              for (var i = 0; i < _filters.length; i++)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: Material(
                    color: _filter == i ? Sb.navy : Sb.chip,
                    shape: const StadiumBorder(),
                    child: InkWell(
                      customBorder: const StadiumBorder(),
                      onTap: () => setState(() => _filter = i),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
                        child: Text(_filters[i],
                            style: TextStyle(
                                fontSize: 14,
                                fontWeight: _filter == i ? FontWeight.w800 : FontWeight.w600,
                                color: _filter == i ? Sb.base : Sb.navy)),
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
        Expanded(
          child: FutureBuilder<List<Exchange>>(
            future: _future,
            builder: (context, snap) {
              if (snap.connectionState != ConnectionState.done && !snap.hasData) return const LoadingView();
              if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
              final list = _apply(snap.data ?? const []);
              if (list.isEmpty) {
                return EmptyView(
                  emoji: '🔁',
                  title: _filter == 0 ? '아직 끝난 교환이 없어요' : '${_filters[_filter]} 내역이 없어요',
                  message: _filter == 0 ? '교환이 성사되거나 거절 · 취소되면 여기에 모여요.' : null,
                );
              }
              return RefreshIndicator(
                onRefresh: () async {
                  setState(_load);
                  await _future;
                },
                child: ListView.builder(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.only(bottom: 24),
                  itemCount: list.length,
                  itemBuilder: (context, i) {
                    final e = list[i];
                    return ExchangeCard(
                      exchange: e,
                      myId: s.session.user?.id ?? -1,
                      onOpenChat: e.chatRoomId == null
                          ? null
                          : () => Navigator.of(context).push(MaterialPageRoute<void>(
                              builder: (_) => ChatRoomScreen(services: s, roomId: e.chatRoomId!))),
                    );
                  },
                ),
              );
            },
          ),
        ),
      ]),
    );
  }
}
