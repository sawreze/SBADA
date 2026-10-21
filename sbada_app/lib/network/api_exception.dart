/// 서버의 오류 응답 {code, message, errors} 와 연결·시간 초과 오류를 같은 모양으로 다룬다(11장).
class ApiException implements Exception {
  ApiException(this.status, this.code, this.message, [this.errors = const {}]);

  /// HTTP 상태. 0 = 서버에 닿지 못함(연결 실패), -1 = 시간 초과
  final int status;
  final String code;
  final String message;
  final Map<String, String> errors;

  factory ApiException.network() =>
      ApiException(0, 'NETWORK', '서버에 연결할 수 없어요. 서버가 켜져 있는지 확인해 주세요.');

  factory ApiException.timeout() =>
      ApiException(-1, 'TIMEOUT', '응답이 늦어요. 잠시 후 다시 시도해 주세요.');

  factory ApiException.fromJson(int status, Map<String, dynamic> json) {
    final rawErrors = json['errors'];
    final errors = <String, String>{};
    if (rawErrors is Map) {
      rawErrors.forEach((k, v) => errors['$k'] = '$v');
    }
    return ApiException(
      status,
      (json['code'] ?? 'HTTP_$status').toString(),
      (json['message'] ?? '요청을 처리하지 못했어요.').toString(),
      errors,
    );
  }

  bool get isUnauthorized => status == 401;
  bool get isForbidden => status == 403;
  bool get isConflict => status == 409;
  bool get isNetwork => status == 0 || status == -1;

  /// 입력칸 아래에 보여 줄 첫 번째 안내
  String? fieldError(String field) => errors[field];

  @override
  String toString() => 'ApiException($status $code: $message)';
}
