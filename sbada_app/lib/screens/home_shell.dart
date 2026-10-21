import 'package:flutter/material.dart';

import '../services/app_services.dart';
import '../widgets/glass.dart';
import 'chat_list_screen.dart';
import 'feed_screen.dart';
import 'map_screen.dart';
import 'profile_screen.dart';
import 'register_item_screen.dart';

/// 하단 탭 4개(떠 있는 유리 탭바): 홈 · 동네지도 · 채팅 · 계정정보
///  - 내 물건 · 알림 · 교환 요청은 '계정정보'와 홈의 알림 종에서 연다.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.services});

  final AppServices services;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  late final List<Widget> _pages;
  late final List<GlassTab> _tabs;

  @override
  void initState() {
    super.initState();
    final s = widget.services;
    s.startRealtime();
    _pages = [
      FeedScreen(services: s),
      MapScreen(services: s),
      ChatListScreen(services: s),
      ProfileScreen(services: s),
    ];
    _tabs = [
      const GlassTab(Icons.home_outlined, Icons.home_rounded, '홈'),
      const GlassTab(Icons.map_outlined, Icons.map_rounded, '동네지도'),
      GlassTab(Icons.chat_bubble_outline_rounded, Icons.chat_bubble_rounded, '채팅', badge: s.unreadChats),
      const GlassTab(Icons.person_outline_rounded, Icons.person_rounded, '계정정보'),
    ];
  }

  Future<void> _register() async {
    final created = await Navigator.of(context).push<bool>(
      MaterialPageRoute(builder: (_) => RegisterItemScreen(services: widget.services)),
    );
    if (created == true) widget.services.itemsChanged.value++;
  }

  void _select(int i) {
    final s = widget.services;
    setState(() => _index = i);
    if (i == 2) s.chatsChanged.value++; // 채팅 탭을 열 때마다 목록을 새로 읽는다
    s.refreshBadges();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // 목록이 유리 탭바 뒤로 비쳐 보이도록 몸통을 탭바 아래까지 늘린다
      extendBody: true,
      body: IndexedStack(index: _index, children: _pages),
      floatingActionButton: _index == 0
          ? Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: GlassFab(label: '물건 올리기', icon: Icons.add_rounded, onPressed: _register),
            )
          : null,
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: GlassTabBar(tabs: _tabs, index: _index, onTap: _select),
    );
  }
}
