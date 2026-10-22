import '../models/app_notification.dart';
import '../network/api_client.dart';

class NotificationRepository {
  NotificationRepository(this.api);

  final ApiClient api;

  Future<List<AppNotification>> list() async {
    final json = await api.get('/api/notifications') as Map<String, dynamic>;
    return (json['notifications'] as List)
        .map((e) => AppNotification.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<int> unreadCount() async {
    final json = await api.get('/api/notifications/unread-count') as Map<String, dynamic>;
    return (json['unread'] as num).toInt();
  }

  Future<void> markRead(int id) async => api.post('/api/notifications/$id/read');

  Future<void> markAllRead() async => api.post('/api/notifications/read-all');

  /// 알림 설정(찜 · 채팅 · 교환 켜기/끄기)
  Future<NotificationSettings> settings() async => NotificationSettings.fromJson(
      await api.get('/api/me/notification-settings') as Map<String, dynamic>);

  /// 바꾼 스위치만 보낸다. 예: {'chat': false}
  Future<NotificationSettings> changeSettings(Map<String, bool> changes) async => NotificationSettings.fromJson(
      await api.put('/api/me/notification-settings', body: changes) as Map<String, dynamic>);

  /// 푸시용 기기 토큰(FCM) 등록 — 로그인 JWT 와는 다른 값(10장 7.3). 응답 fcmEnabled = 서버가 실제로 보낼 수 있는지
  Future<bool> registerDevice(String token, String platform) async {
    final json = await api.post('/api/devices', body: {'token': token, 'platform': platform}) as Map<String, dynamic>;
    return json['fcmEnabled'] == true;
  }

  /// 로그아웃할 때 이 기기 토큰을 지운다(다른 사람이 이 폰으로 로그인하기 전까지 내 알림이 오지 않게)
  Future<void> unregisterDevice(String token) async =>
      api.post('/api/devices/remove', body: {'token': token});
}
