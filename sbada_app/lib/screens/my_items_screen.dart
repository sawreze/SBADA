import 'package:flutter/material.dart';

import '../models/item.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import 'item_detail_screen.dart';
import 'match_screen.dart';

/// 내 물건 목록. 물건을 누르면 '비슷한 가격대 매칭'이 열린다.
class MyItemsScreen extends StatefulWidget {
  const MyItemsScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<MyItemsScreen> createState() => _MyItemsScreenState();
}

class _MyItemsScreenState extends State<MyItemsScreen> {
  late Future<List<Item>> _future;

  @override
  void initState() {
    super.initState();
    _load();
    widget.services.itemsChanged.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.services.itemsChanged.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(_load);
  }

  void _load() => _future = widget.services.items.mine();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('내 물건')),
      body: FutureBuilder<List<Item>>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done && !snap.hasData) return const LoadingView();
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
          final items = snap.data ?? const [];
          if (items.isEmpty) {
            return const EmptyView(
              emoji: '📸',
              title: '아직 올린 물건이 없어요',
              message: '홈의 \'물건 올리기\'로 사진을 찍으면\nAI가 가격을 매기고 바꿀 물건을 찾아 줘요.',
            );
          }
          return RefreshIndicator(
            onRefresh: () async {
              setState(_load);
              await _future;
            },
            child: ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(bottom: 96),
              itemCount: items.length,
              separatorBuilder: (context, i) => const Divider(height: 1, indent: 20, endIndent: 20),
              itemBuilder: (context, i) {
                final item = items[i];
                return ItemTile(
                  item: item,
                  onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(
                    builder: (_) => item.isAvailable
                        ? MatchScreen(services: widget.services, item: item)
                        : ItemDetailScreen(services: widget.services, itemId: item.id),
                  )),
                  footer: item.isAvailable
                      ? Pill('비슷한 가격대 매칭 보기 →', color: Sb.blueSoft, textColor: Sb.mintText)
                      : Pill('교환 완료', color: Sb.greenSoft, textColor: Sb.green),
                );
              },
            ),
          );
        },
      ),
    );
  }
}
