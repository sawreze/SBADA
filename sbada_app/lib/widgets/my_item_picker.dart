import 'package:flutter/material.dart';

import '../models/item.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import 'common.dart';
import 'net_image.dart';

/// 결과: 고른 내 물건 + 함께 보낼 메시지
class OfferChoice {
  const OfferChoice(this.item, this.message);

  final Item item;
  final String? message;
}

/// '바로 바꾸기'에서 내놓을 내 물건을 고르는 바텀시트.
/// 상대 물건과의 가격 차이를 함께 보여 줘서 비슷한 가격대를 고르기 쉽게 한다.
Future<OfferChoice?> pickMyItem(BuildContext context, AppServices services, {required Item target}) {
  return showModalBottomSheet<OfferChoice>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    backgroundColor: Sb.card,
    builder: (context) => _MyItemPicker(services: services, target: target),
  );
}

class _MyItemPicker extends StatefulWidget {
  const _MyItemPicker({required this.services, required this.target});

  final AppServices services;
  final Item target;

  @override
  State<_MyItemPicker> createState() => _MyItemPickerState();
}

class _MyItemPickerState extends State<_MyItemPicker> {
  late Future<List<Item>> _future;
  Item? _selected;
  final _message = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _future = widget.services.items.mine().then((list) {
      final available = list.where((i) => i.isAvailable).toList();
      // 가격이 가까운 순서로 정렬
      available.sort((a, b) => (a.price - widget.target.price).abs().compareTo((b.price - widget.target.price).abs()));
      return available;
    });
  }

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final height = MediaQuery.of(context).size.height * 0.75;
    return SizedBox(
      height: height,
      child: Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 4),
            child: Text("'${widget.target.title}'와 바꿀\n내 물건을 골라 주세요", style: sbH2),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
            child: Text('상대 물건 AI 추정가 ${formatWon(widget.target.price)} · 상대가 수락하면 교환이 완료돼요', style: sbCaption),
          ),
          Expanded(
            child: FutureBuilder<List<Item>>(
              future: _future,
              builder: (context, snap) {
                if (snap.connectionState != ConnectionState.done) return const LoadingView();
                if (snap.hasError) {
                  return ErrorView(error: snap.error!, onRetry: () => setState(_load));
                }
                final list = snap.data!;
                if (list.isEmpty) {
                  return const EmptyView(
                      emoji: '📦', title: '교환할 내 물건이 없어요', message: '먼저 물건을 올리면 바로 바꾸기를 할 수 있어요.');
                }
                return ListView.builder(
                  itemCount: list.length,
                  itemBuilder: (context, i) {
                    final item = list[i];
                    final diff = widget.target.price == 0
                        ? 0
                        : ((item.price - widget.target.price) * 100 / widget.target.price).round();
                    final selected = _selected?.id == item.id;
                    return ListTile(
                      onTap: () => setState(() => _selected = item),
                      leading: NetImage(item.firstImage, size: 52, radius: 10),
                      title: Text(item.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                      subtitle: Text('${formatWon(item.price)} · 가격 차이 ${formatPercent(diff)}'),
                      trailing: Icon(selected ? Icons.check_circle : Icons.radio_button_unchecked,
                          color: selected ? Sb.blue : Sb.line),
                    );
                  },
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 8),
            child: TextField(
              controller: _message,
              maxLength: 200,
              decoration: sbInput('함께 보낼 말 (선택)', hint: '예) 직거래 가능해요!'),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
            child: FilledButton(
              onPressed: _selected == null
                  ? null
                  : () => Navigator.pop(
                      context, OfferChoice(_selected!, _message.text.trim().isEmpty ? null : _message.text.trim())),
              child: const Text('교환 요청 보내기'),
            ),
          ),
        ]),
      ),
    );
  }
}
