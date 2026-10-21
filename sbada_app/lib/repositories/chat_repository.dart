import '../models/chat.dart';
import '../network/api_client.dart';

class ChatRepository {
  ChatRepository(this.api);

  final ApiClient api;

  /// 물건 상세의 '채팅하기'. 같은 물건이면 기존 방을 돌려준다.
  Future<ChatRoom> open(int itemId) async =>
      ChatRoom.fromJson(await api.post('/api/chats', body: {'itemId': itemId}) as Map<String, dynamic>);

  Future<List<ChatRoom>> rooms() async {
    final json = await api.get('/api/chats') as Map<String, dynamic>;
    return (json['rooms'] as List).map((e) => ChatRoom.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<ChatRoom> room(int id) async => ChatRoom.fromJson(await api.get('/api/chats/$id') as Map<String, dynamic>);

  /// afterId 가 있으면 그 뒤의 메시지만(재접속 후 놓친 메시지 채우기)
  Future<List<ChatMessage>> messages(int roomId, {int? afterId}) async {
    final json = await api.get('/api/chats/$roomId/messages',
        query: afterId == null ? null : {'afterId': '$afterId'}) as Map<String, dynamic>;
    return (json['messages'] as List).map((e) => ChatMessage.fromJson(e as Map<String, dynamic>)).toList();
  }

  /// WebSocket 이 끊겼을 때 REST 로 보낸다.
  Future<ChatMessage> sendRest(int roomId, String content) async => ChatMessage.fromJson(
      await api.post('/api/chats/$roomId/messages', body: {'content': content}) as Map<String, dynamic>);

  Future<void> markRead(int roomId) async => api.post('/api/chats/$roomId/read');
}
