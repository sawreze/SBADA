import 'package:flutter/material.dart';

import '../models/exchange.dart';
import '../models/item.dart';
import '../models/user.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/expand_route.dart';
import '../widgets/item_card.dart';
import 'item_detail_screen.dart';

// 2026-11-25 사용자 요청: 프로필을 누르면 그 사람의 물건 목록 + 그 아래에 거래 후기 목록(별도)
//   물건: GraphQL userItems(userId) · 후기: GET /api/users/{id}/reviews (둘 다 로그인 없이 볼 수 있음)

class UserProfileScreen extends StatefulWidget {
  const UserProfileScreen({super.key, required this.services, required this.user});

  final AppServices services;
  final PublicUser user;

  @override
  State<UserProfileScreen> createState() => _UserProfileScreenState();
}

class _UserProfileScreenState extends State<UserProfileScreen> {
  late Future<(List<Item>, ReviewList)> _future = _load();

  Future<(List<Item>, ReviewList)> _load() async {
    final s = widget.services;
    final results = await Future.wait<Object>([
      s.items.userItems(widget.user.id),
      s.exchanges.reviewsOf(widget.user.id),
    ]);
    return (results[0] as List<Item>, results[1] as ReviewList);
  }

  @override
  Widget build(BuildContext context) {
    final u = widget.user;
    return Scaffold(
      appBar: AppBar(title: Text(u.nickname)),
      body: FutureBuilder<(List<Item>, ReviewList)>(
        future: _future,
        builder: (context, snap) {
          if (snap.hasError) {
            return ErrorView(error: snap.error!, onRetry: () => setState(() => _future = _load()));
          }
          if (!snap.hasData) return const LoadingView();
          final (items, reviews) = snap.data!;
          return RefreshIndicator(
            onRefresh: () async {
              setState(() => _future = _load());
              await _future;
            },
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(bottom: 32),
              children: [
                _header(u, reviews, items.length),
                _section('물건 ${items.length}개', key: const ValueKey('profile-items')),
                if (items.isEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
                    child: Text('올린 물건이 아직 없어요.', style: sbCaption),
                  )
                else
                  for (final it in items)
                    ItemTile(
                      item: it,
                      onTap: () => Navigator.of(context).push(appRoute<void>(
                          builder: (_) => ItemDetailScreen(services: widget.services, itemId: it.id))),
                    ),
                const SizedBox(height: 8),
                _section('거래 후기 ${reviews.count}개', key: const ValueKey('profile-reviews')),
                if (reviews.reviews.isEmpty)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
                    child: Text('아직 받은 거래 후기가 없어요. 거래가 끝나면 서로 후기를 남길 수 있어요.', style: sbCaption),
                  )
                else
                  for (final r in reviews.reviews) _ReviewTile(review: r),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _header(PublicUser u, ReviewList reviews, int itemCount) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
      child: Row(children: [
        CircleAvatar(
          radius: 30,
          backgroundColor: Sb.blueSoft,
          child: Text(u.nickname.isEmpty ? '?' : u.nickname.characters.first,
              style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800, color: Sb.mintText)),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(u.nickname, style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: Sb.navy)),
            if (u.region.isNotEmpty) Text(u.region, style: sbCaption),
            const SizedBox(height: 6),
            Row(children: [
              const Icon(Icons.star_rounded, color: Color(0xFFFFB800), size: 20),
              const SizedBox(width: 3),
              Text(reviews.count == 0 ? '후기 없음' : reviews.average.toStringAsFixed(1),
                  key: const ValueKey('profile-rating'),
                  style: TextStyle(fontWeight: FontWeight.w700, color: Sb.navy)),
              Text('  ·  후기 ${reviews.count}  ·  물건 $itemCount', style: sbCaption),
            ]),
          ]),
        ),
      ]),
    );
  }

  Widget _section(String title, {Key? key}) => Padding(
        key: key,
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 6),
        child: Text(title, style: TextStyle(fontSize: 17, fontWeight: FontWeight.w800, color: Sb.navy)),
      );
}

class _ReviewTile extends StatelessWidget {
  const _ReviewTile({required this.review});

  final Review review;

  @override
  Widget build(BuildContext context) {
    final r = review;
    final t = r.createdAt.toLocal();
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: Sb.bg, borderRadius: BorderRadius.circular(16)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          for (var i = 1; i <= 5; i++)
            Icon(i <= r.rating ? Icons.star_rounded : Icons.star_outline_rounded, size: 18, color: const Color(0xFFFFB800)),
          const SizedBox(width: 8),
          Expanded(
            child: Text(r.writer.nickname,
                maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
          ),
          Text('${t.year}.${t.month}.${t.day}', style: sbCaption),
        ]),
        if (r.content != null && r.content!.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(r.content!, style: sbBody),
        ],
      ]),
    );
  }
}
