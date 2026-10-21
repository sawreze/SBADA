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
}
