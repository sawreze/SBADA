import 'dart:async';

import 'package:flutter/foundation.dart';

import '../config.dart';
import '../models/app_notification.dart';
import '../network/api_client.dart';
import '../network/realtime_client.dart';
import '../network/session.dart';
import '../repositories/auth_repository.dart';
import '../repositories/chat_repository.dart';
import '../repositories/exchange_repository.dart';
import '../repositories/item_repository.dart';
import '../repositories/notification_repository.dart';
import 'dong_finder.dart';
import 'local_notifier.dart';
import 'push_service.dart';

/// 앱 전체가 함께 쓰는 객체 묶음. 화면에는 생성자로 넘긴다(9장의 '저장소 주입').
class AppServices {
  AppServices({
    required this.session,
    required this.api,
    required this.realtime,
    required this.notifier,
  })  : auth = AuthRepository(api, session),
        items = ItemRepository(api),
        exchanges = ExchangeRepository(api),
        chats = ChatRepository(api),
        notifications = NotificationRepository(api) {
    push = PushService(notifications);
  }

  final Session session;
  final ApiClient api;
  final RealtimeClient realtime;
  final LocalNotifier notifier;
  final AuthRepository auth;
  final ItemRepository items;
  final ExchangeRepository exchanges;
  final ChatRepository chats;
  final NotificationRepository notifications;

  /// 앱이 꺼져 있을 때 받는 푸시(FCM / APNs)
  late final PushService push;

  /// 알림 설정(찜 · 채팅 · 교환 켜기/끄기). 끈 종류는 앱이 켜져 있을 때도 배너를 띄우지 않는다
  final ValueNotifier<NotificationSettings> notificationSettings = ValueNotifier(const NotificationSettings());

  Future<void> refreshNotificationSettings() async {
    if (!session.isLoggedIn) return;
    try {
      notificationSettings.value = await notifications.settings();
    } catch (_) {
      // 못 읽으면 기본값(모두 켜짐)
    }
  }

  /// 하단 탭의 빨간 숫자
  final ValueNotifier<int> unreadNotifications = ValueNotifier(0);
  final ValueNotifier<int> unreadChats = ValueNotifier(0);

  /// 물건·교환 상태가 바뀌면 1씩 늘린다 → 홈·내 물건 화면이 다시 불러온다
  final ValueNotifier<int> itemsChanged = ValueNotifier(0);

  /// 채팅방 목록을 다시 읽어야 할 때 1씩 늘린다(새 메시지·교환 상태 변경·채팅 탭 열기)
  final ValueNotifier<int> chatsChanged = ValueNotifier(0);

  /// 지금 열려 있는 채팅방(그 방의 새 메시지는 Android 알림을 띄우지 않는다)
  int? activeRoomId;

  /// 내가 찜한 물건 번호들(상세 화면 하트 · 관심목록 · 계정정보 숫자가 같이 본다). 로그인 후 서버에서 받아 온다.
  final ValueNotifier<Set<int>> likedIds = ValueNotifier(const {});

  Future<void> refreshLikes() async {
    if (!session.isLoggedIn) return;
    try {
      likedIds.value = {for (final it in await items.likes()) it.id};
    } catch (_) {
      // 하트 표시만 늦어질 뿐 화면은 막지 않는다
    }
  }

  /// 찜하기 / 해제. 화면에는 먼저 반영하고(바로 반응), 서버가 실패하면 되돌린 뒤 오류를 던진다.
  Future<void> toggleLike(int itemId) async {
    final before = likedIds.value;
    final liked = !before.contains(itemId);
    likedIds.value = liked ? {...before, itemId} : ({...before}..remove(itemId));
    try {
      await items.setLiked(itemId, liked);
    } catch (_) {
      likedIds.value = before;
      rethrow;
    }
  }

  StreamSubscription<Map<String, dynamic>>? _sub;

  static Future<AppServices> create() async {
    final session = Session(PrefsStore());
    await session.load();
    await DongFinder.load(session.store); // GPS 로 찾은 내 동네 좌표(지도 중심)
    final api = ApiClient(baseUrl: AppConfig.apiBaseUrl, session: session);
    return AppServices(
      session: session,
      api: api,
      realtime: RealtimeClient(wsUrl: AppConfig.wsUrl, session: session),
      notifier: LocalNotifier(),
    );
  }

  /// 로그인 뒤 홈 화면이 열릴 때 부른다.
  void startRealtime() {
    realtime.connect();
    _sub ??= realtime.events.listen(_onEvent);
    unawaited(notifier.requestPermission());
    unawaited(refreshNotificationSettings());
    unawaited(push.start()); // FCM 기기 토큰 등록(설정 파일이 없으면 조용히 건너뜀)
    unawaited(refreshBadges());
    unawaited(refreshLikes());
  }

  void _onEvent(Map<String, dynamic> event) {
    final me = session.user?.id;
    switch (event['type']) {
      case 'notification':
        final n = event['notification'];
        if (n is Map<String, dynamic>) {
          unreadNotifications.value = unreadNotifications.value + 1;
          // 서버 → WebSocket → 앱 → Android 알림 표시줄. 알림 설정에서 끈 종류는 서버가 silent 로 보낸다(배지만)
          final silent = event['silent'] == true || !notificationSettings.value.allows('${n['type']}');
          if (!silent) unawaited(notifier.show('${n['title'] ?? '스바다'}', '${n['body'] ?? ''}'));
          if ('${n['type']}'.startsWith('EXCHANGE')) itemsChanged.value++;
        }
      case 'exchange.updated':
        itemsChanged.value++;
        chatsChanged.value++;
      case 'chat.message':
        chatsChanged.value++;
        final m = event['message'];
        if (m is Map<String, dynamic>) {
          final fromOther = m['senderId'] != null && m['senderId'] != me;
          final roomId = m['roomId'];
          if (fromOther && roomId != activeRoomId) {
            unreadChats.value = unreadChats.value + 1;
            if (notificationSettings.value.chat) {
              unawaited(notifier.show('${m['senderNickname'] ?? '새 메시지'}', '${m['content'] ?? ''}'));
            }
          }
        }
    }
  }

  Future<void> refreshBadges() async {
    if (!session.isLoggedIn) return;
    try {
      unreadNotifications.value = await notifications.unreadCount();
      final rooms = await chats.rooms();
      unreadChats.value = rooms.fold(0, (sum, r) => sum + r.unread);
    } catch (_) {
      // 배지는 실패해도 화면을 막지 않는다
    }
  }

  Future<void> logout() async {
    await push.stop(); // 이 폰에 내 푸시가 더 오지 않게(토큰이 있어야 해서 세션 지우기 전에)
    await realtime.disconnect();
    await _sub?.cancel();
    _sub = null;
    unreadChats.value = 0;
    unreadNotifications.value = 0;
    likedIds.value = const {};
    notificationSettings.value = const NotificationSettings();
    await session.signOut();
  }
}
