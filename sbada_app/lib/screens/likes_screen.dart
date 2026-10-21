import 'package:flutter/material.dart';

import '../models/item.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import 'item_detail_screen.dart';

/// 관심목록(찜한 물건). 계정정보 메뉴 3번째 · 찜 팝업의 '관심목록 보기'에서 연다.
/// GET /api/me/likes(최근 찜한 순). 오른쪽 하트를 누르면 찜 해제(DELETE /api/items/{id}/like).
class LikesScreen extends StatefulWidget {
  const LikesScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<LikesScreen> createState() => _LikesScreenState();
}

class _LikesScreenState extends State<LikesScreen> {
  late Future<List<Item>> _future;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = widget.services.items.likes().then((list) {
      // 서버 목록을 기준으로 하트 표시도 맞춘다
      widget.services.likedIds.value = {for (final it in list) it.id};
      return list;
    });
  }

  Future<void> _open(Item item) async {
    await Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => ItemDetailScreen(services: widget.services, itemId: item.id)));
    if (mounted) setState(_load); // 상세에서 찜을 풀었을 수 있다
  }

  Future<void> _unlike(Item item) async {
    try {
      await widget.services.toggleLike(item.id);
      if (mounted) setState(_load);
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('관심목록', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
      body: FutureBuilder<List<Item>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done && !snap.hasData) return const LoadingView();
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
          final list = snap.data ?? const [];
          if (list.isEmpty) {
            return const EmptyView(
              emoji: '🤍',
              title: '아직 관심목록이 비어 있어요',
              message: '마음에 드는 물건의 왼쪽 아래 하트를 눌러 보세요.',
            );
          }
          return RefreshIndicator(
            onRefresh: () async {
              setState(_load);
              await _future;
            },
            child: ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(bottom: 24),
              itemCount: list.length,
              separatorBuilder: (context, i) => const Divider(height: 1, indent: 16, endIndent: 16),
              itemBuilder: (context, i) {
                final it = list[i];
                return ItemTile(
                  item: it,
                  onTap: () => _open(it),
                  trailing: IconButton(
                    tooltip: '찜 해제',
                    onPressed: () => _unlike(it),
                    icon: Icon(Icons.favorite_rounded, color: Sb.isDark ? Sb.blue : Sb.mintText),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }
}
