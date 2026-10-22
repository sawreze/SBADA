import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../models/user.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../services/theme_controller.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/glass.dart';
import '../widgets/net_image.dart';
import '../widgets/settings_group.dart';
import 'exchange_history_screen.dart';
import 'likes_screen.dart';
import 'login_screen.dart';
import 'my_info_screen.dart';
import 'my_items_screen.dart';
import 'notification_settings_screen.dart';
import 'notifications_screen.dart';
import 'onboarding_screen.dart';
import 'region_screen.dart';
import 'settings_screen.dart';

/// 계정정보: 프로필 카드 + 묶음 메뉴(나의 거래 · 설정) + 로그아웃.
/// 예전 아래쪽의 학습 선호도 · 연결 상태는 '앱 설정'으로 옮겼다(2026-10-21 사용자 요청).
class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  late Future<MeProfile> _future;

  /// 메뉴 오른쪽 숫자: 내 물건 개수 · 아직 답하지 않은 받은 교환 요청 수(불러오기 전에는 null)
  int? _myItemCount;
  final ValueNotifier<int> _pendingReceived = ValueNotifier(0);

  @override
  void initState() {
    super.initState();
    _load();
    widget.services.itemsChanged.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.services.itemsChanged.removeListener(_onChanged);
    _pendingReceived.dispose();
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(_load);
  }

  void _load() {
    final s = widget.services;
    _future = s.auth.me();
    // 메뉴 숫자는 실패해도 화면을 막지 않는다(숫자만 안 보임)
    s.items.mine().then((v) {
      if (mounted) setState(() => _myItemCount = v.length);
    }).catchError((Object _) {});
    s.exchanges.list(received: true).then((v) {
      if (mounted) _pendingReceived.value = v.where((e) => e.status == 'REQUESTED').length;
    }).catchError((Object _) {});
  }

  Future<void> _editPreferences(MeProfile me) async {
    final changed = await Navigator.of(context).push<bool>(MaterialPageRoute(
      builder: (_) => OnboardingScreen(services: widget.services, mode: OnboardingMode.edit, initial: me),
    ));
    if (changed == true && mounted) {
      setState(_load);
      widget.services.itemsChanged.value++;
    }
  }

  bool _uploadingPhoto = false;

  /// 프로필 사진: 앨범(웹은 파일)에서 고름 → POST /api/images 로 올림 → PUT /api/me/photo
  Future<void> _pickPhoto() async {
    if (_uploadingPhoto) return;
    try {
      final file = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 800, maxHeight: 800, imageQuality: 85);
      if (file == null) return; // 취소
      setState(() => _uploadingPhoto = true);
      final bytes = await file.readAsBytes();
      if (bytes.length > 5 * 1024 * 1024) {
        if (mounted) showSnack(context, '사진이 너무 커요(5MB 이하).', error: true);
        return;
      }
      final imageId = await widget.services.items.uploadImage(bytes, file.name.isEmpty ? 'profile.jpg' : file.name);
      await widget.services.auth.setPhoto(imageId);
      if (!mounted) return;
      setState(_load);
      showSnack(context, '프로필 사진을 바꿨어요.');
    } on ApiException catch (e) {
      if (mounted) showSnack(context, e.message, error: true);
    } catch (e) {
      if (mounted) showSnack(context, '사진을 가져오지 못했어요: $e', error: true);
    } finally {
      if (mounted) setState(() => _uploadingPhoto = false);
    }
  }

  Future<void> _logout() async {
    await widget.services.logout();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute<void>(builder: (_) => LoginScreen(services: widget.services)),
      (route) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      // 화이트 모드: 연회색 바탕 + 흰 카드(다크는 예전 그대로)
      backgroundColor: Sb.groupedBg,
      appBar: AppBar(
        backgroundColor: Sb.groupedBg,
        title: const Text('계정정보'),
        actions: const [ThemeToggle(), SizedBox(width: 12)],
      ),
      body: FutureBuilder<MeProfile>(
        future: _future,
        builder: (context, snap) {
          if (snap.connectionState != ConnectionState.done && !snap.hasData) return const LoadingView();
          if (snap.hasError) return ErrorView(error: snap.error!, onRetry: () => setState(_load));
          final me = snap.data!;
          return RefreshIndicator(
            onRefresh: () async {
              setState(_load);
              await _future;
            },
            child: ListView(padding: const EdgeInsets.only(bottom: Sb.tabBarSpace + 16), children: [
              _profileCard(me),
              // 사용자 시안: 묶음 카드 · 왼쪽 아이콘 · 오른쪽 > . 관심목록은 3번째.
              SettingsGroup(title: '나의 거래', children: [
                SettingsRow(
                  icon: Icons.inventory_2_outlined,
                  label: '내 물건',
                  trailing: _myItemCount == null ? null : TrailingText('$_myItemCount개'),
                  onTap: () => _push(MyItemsScreen(services: widget.services)),
                ),
                SettingsRow(
                  icon: Icons.move_to_inbox_outlined,
                  label: '받은 교환 요청',
                  trailing: CountBadge(_pendingReceived),
                  onTap: () => _push(NotificationsScreen(services: widget.services, initialTab: 1)),
                ),
                ValueListenableBuilder<Set<int>>(
                  valueListenable: widget.services.likedIds,
                  builder: (context, ids, child) => SettingsRow(
                    icon: Icons.favorite_border_rounded,
                    label: '관심목록',
                    trailing: TrailingText('${ids.length}개'),
                    onTap: () => _push(LikesScreen(services: widget.services)),
                  ),
                ),
                SettingsRow(
                  icon: Icons.outbox_outlined,
                  label: '보낸 교환 요청',
                  onTap: () => _push(NotificationsScreen(services: widget.services, initialTab: 2)),
                ),
                SettingsRow(
                  icon: Icons.history_rounded,
                  label: '교환 내역',
                  onTap: () => _push(ExchangeHistoryScreen(services: widget.services)),
                ),
              ]),
              SettingsGroup(title: '설정', children: [
                SettingsRow(
                  icon: Icons.notifications_none_rounded,
                  label: '알림',
                  trailing: CountBadge(widget.services.unreadNotifications),
                  onTap: () => _push(NotificationsScreen(services: widget.services)),
                ),
                // 찜 · 채팅 · 교환 알림 켜기/끄기 + 푸시(FCM/APNs) 상태
                SettingsRow(
                  key: const ValueKey('notification-settings'),
                  icon: Icons.tune_outlined,
                  label: '알림 설정',
                  subtitle: '찜 · 채팅 · 교환 알림 켜기/끄기',
                  onTap: () => _push(NotificationSettingsScreen(services: widget.services)),
                ),
                SettingsRow(
                  icon: Icons.tune_rounded,
                  label: '취향 다시 고르기',
                  onTap: () => _editPreferences(me),
                ),
                SettingsRow(
                  icon: Icons.place_outlined,
                  label: '내 동네 설정',
                  trailing: TrailingText(me.user.region.split(' ').last),
                  onTap: () => _push(RegionScreen(services: widget.services)),
                ),
                // 내 동네는 최대 2개: 하나 더 추가하는 칸(추가하면 홈 왼쪽 위에서 바꿀 수 있다)
                me.user.secondRegion == null
                    ? SettingsRow(
                        key: const ValueKey('add-second-region'),
                        icon: Icons.add_location_alt_outlined,
                        label: '동네 추가',
                        subtitle: '최대 2개 · 홈 왼쪽 위 동네 이름을 눌러 바꿔요',
                        trailing: Icon(Icons.add_rounded, color: Sb.mintText),
                        showChevron: false,
                        onTap: () => _push(RegionScreen(services: widget.services, second: true)),
                      )
                    : SettingsRow(
                        key: const ValueKey('second-region'),
                        icon: Icons.add_location_alt_outlined,
                        label: '추가한 동네',
                        subtitle: '홈 왼쪽 위 동네 이름을 눌러 바꿔요',
                        trailing: TrailingText(me.user.secondRegion!.split(' ').last),
                        onTap: () => _push(RegionScreen(services: widget.services, second: true)),
                      ),
                SettingsRow(
                  icon: Icons.settings_outlined,
                  label: '앱 설정',
                  onTap: () => _push(SettingsScreen(services: widget.services)),
                ),
                SettingsRow(
                  icon: Icons.badge_outlined,
                  label: '내 정보',
                  subtitle: '닉네임 · 아이디 · 비밀번호',
                  onTap: () => _push(MyInfoScreen(services: widget.services)),
                ),
              ]),
              const SizedBox(height: 12),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: OutlinedButton(
                  onPressed: _logout,
                  style: OutlinedButton.styleFrom(foregroundColor: Sb.red),
                  child: const Text('로그아웃'),
                ),
              ),
            ]),
          );
        },
      ),
    );
  }

  Future<void> _push(Widget page) async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));
    widget.services.refreshBadges();
    if (mounted) setState(_load);
  }

  /// 디자인 시안(계정정보): 아바타 60 · 닉네임 · 동네 · 직접 고른 관심 종류 칩
  Widget _profileCard(MeProfile me) {
    final picked = me.preferences.where((p) => p.source == 'ONBOARDING').take(3).toList();
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 16),
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(color: Sb.groupCard, borderRadius: Sb.r),
      child: Row(children: [
        // 프로필 사진(없으면 닉네임 첫 글자) + 오른쪽 아래 카메라 버튼
        Semantics(
          button: true,
          label: '프로필 사진 바꾸기',
          child: GestureDetector(
            onTap: _pickPhoto,
            child: SizedBox(
              width: 64,
              height: 64,
              child: Stack(clipBehavior: Clip.none, children: [
                Positioned.fill(
                  child: ClipOval(
                    child: me.user.profileImageUrl != null
                        ? NetImage(me.user.profileImageUrl, size: 64, radius: 0)
                        : Container(
                            alignment: Alignment.center,
                            color: Sb.blueSoft,
                            child: Text(me.user.nickname.characters.first,
                                style: TextStyle(fontSize: 22, color: Sb.mintText, fontWeight: FontWeight.w800)),
                          ),
                  ),
                ),
                if (_uploadingPhoto)
                  const Positioned.fill(
                    child: ClipOval(
                      child: ColoredBox(
                        color: Colors.black45,
                        child: Center(
                          child: SizedBox(
                              width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white)),
                        ),
                      ),
                    ),
                  ),
                Positioned(
                  right: -2,
                  bottom: -2,
                  child: Container(
                    width: 24,
                    height: 24,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: Sb.isDark ? Sb.chip : Sb.bg,
                      border: Border.all(color: Sb.groupCard, width: 2),
                    ),
                    child: Icon(Icons.photo_camera_rounded, size: 13, color: Sb.navy),
                  ),
                ),
              ]),
            ),
          ),
        ),
        const SizedBox(width: 16),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(me.user.nickname, style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: Sb.navy)),
            const SizedBox(height: 2),
            Text('${me.user.region} · @${me.user.username}',
                style: TextStyle(fontSize: 13, color: Sb.text3), maxLines: 1, overflow: TextOverflow.ellipsis),
            if (picked.isNotEmpty) ...[
              const SizedBox(height: 8),
              Wrap(spacing: 6, runSpacing: 6, children: [
                for (final p in picked) Pill(p.label, color: Sb.blueSoft, textColor: Sb.mintText, bold: true),
              ]),
            ],
          ]),
        ),
      ]),
    );
  }
}

/// 다크 / 화이트 모드 전환 스위치(유리 캡슐 안의 ☀️ · 🌙). 선택된 쪽이 민트로 채워진다.
/// 크기: 높이 44dp, 칸마다 48dp 폭(안드로이드 최소 터치 영역)
class ThemeToggle extends StatelessWidget {
  const ThemeToggle({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: Sb.darkMode,
      builder: (context, dark, child) => Glass(
        tint: Sb.glassButton,
        blur: 12,
        child: Padding(
          padding: const EdgeInsets.all(3),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            _option(Icons.light_mode_rounded, '화이트 모드', selected: !dark, onTap: () => ThemeController.setDark(false)),
            _option(Icons.dark_mode_rounded, '다크 모드', selected: dark, onTap: () => ThemeController.setDark(true)),
          ]),
        ),
      ),
    );
  }

  Widget _option(IconData icon, String label, {required bool selected, required VoidCallback onTap}) {
    return Semantics(
      button: true,
      selected: selected,
      label: label,
      child: Tooltip(
        message: label,
        child: InkWell(
          customBorder: const StadiumBorder(),
          onTap: onTap,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            width: 48,
            height: 38,
            decoration: ShapeDecoration(
              shape: const StadiumBorder(),
              color: selected ? Sb.blue : Colors.transparent,
            ),
            child: Icon(icon, size: 20, color: selected ? Sb.onAccent : Sb.text2),
          ),
        ),
      ),
    );
  }
}
