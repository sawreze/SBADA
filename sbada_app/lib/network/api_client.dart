import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import 'api_exception.dart';
import 'session.dart';

/// 모든 HTTP 요청이 지나가는 한 곳(10장 2.7 ③).
///  - Bearer 토큰은 여기서만 붙인다.
///  - 응답 본문은 UTF-8 로 해석한 뒤 JSON 으로 읽는다(한글 깨짐 방지).
///  - 시간 제한은 '보내기 ~ 본문 읽기' 전체에 건다(11장 4.1의 실제 수정 사례).
///  - 204 처럼 본문이 없는 성공은 null 로 돌려준다(7장 4.4).
class ApiClient {
  ApiClient({
    required this.baseUrl,
    required this.session,
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : _client = client ?? http.Client();

  final String baseUrl;
  final Session session;
  final Duration timeout;
  final http.Client _client;

  /// 401(만료·무효 토큰)을 받았을 때 화면 쪽에서 로그인으로 보내기 위한 연결점
  void Function()? onUnauthorized;

  Uri uri(String path, [Map<String, String>? query]) {
    final u = Uri.parse('$baseUrl$path');
    return query == null || query.isEmpty ? u : u.replace(queryParameters: query);
  }

  Future<dynamic> get(String path, {Map<String, String>? query, bool auth = true}) =>
      _send('GET', path, query: query, auth: auth);

  Future<dynamic> post(String path, {Object? body, bool auth = true, Duration? timeout}) =>
      _send('POST', path, body: body, auth: auth, timeout: timeout);

  Future<dynamic> put(String path, {Object? body}) => _send('PUT', path, body: body);

  Future<dynamic> delete(String path) => _send('DELETE', path);

  Future<dynamic> _send(
    String method,
    String path, {
    Object? body,
    Map<String, String>? query,
    bool auth = true,
    Duration? timeout,
  }) async {
    final req = http.Request(method, uri(path, query));
    req.headers['Accept'] = 'application/json';
    final token = session.token;
    if (auth && token != null) {
      req.headers['Authorization'] = 'Bearer $token';
    }
    if (body != null) {
      req.headers['Content-Type'] = 'application/json; charset=utf-8';
      req.body = jsonEncode(body);
    }
    return _execute(req, timeout ?? this.timeout);
  }

  /// multipart/form-data 의 file 항목으로 사진 바이트를 보낸다(7장 9.2). boundary 는 http 패키지가 만든다.
  Future<Map<String, dynamic>> uploadImage(Uint8List bytes, String filename) async {
    final req = http.MultipartRequest('POST', uri('/api/images'));
    req.headers['Accept'] = 'application/json';
    final token = session.token;
    if (token != null) req.headers['Authorization'] = 'Bearer $token';
    req.files.add(http.MultipartFile.fromBytes('file', bytes, filename: filename));
    final result = await _execute(req, const Duration(seconds: 40));
    return result as Map<String, dynamic>;
  }

  Future<dynamic> _execute(http.BaseRequest req, Duration limit) async {
    http.Response res;
    try {
      res = await _client.send(req).then(http.Response.fromStream).timeout(limit);
    } on TimeoutException {
      throw ApiException.timeout();
    } on SocketException {
      throw ApiException.network();
    } on http.ClientException {
      throw ApiException.network();
    }
    final text = res.bodyBytes.isEmpty ? '' : utf8.decode(res.bodyBytes, allowMalformed: true);
    dynamic json;
    if (text.isNotEmpty) {
      try {
        json = jsonDecode(text);
      } catch (_) {
        json = null;
      }
    }
    if (res.statusCode >= 200 && res.statusCode < 300) {
      return json;
    }
    final error = json is Map<String, dynamic>
        ? ApiException.fromJson(res.statusCode, json)
        : ApiException(res.statusCode, 'HTTP_${res.statusCode}', '요청을 처리하지 못했어요(${res.statusCode}).');
    if (error.isUnauthorized && session.token != null && error.code != 'LOGIN_FAILED') {
      onUnauthorized?.call();
    }
    throw error;
  }
}
