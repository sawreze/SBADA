/// JSON 값을 모델로 옮길 때 쓰는 작은 도우미.
/// 필수 값이 빠졌는데 그럴듯한 기본값으로 숨기지 않는다(10장 3.2) → FormatException.
int reqInt(Map<String, dynamic> j, String key) {
  final v = j[key];
  if (v is int) return v;
  if (v is num) return v.toInt();
  throw FormatException('필수 숫자 "$key" 가 없거나 형식이 다릅니다: $v');
}

String reqStr(Map<String, dynamic> j, String key) {
  final v = j[key];
  if (v is String) return v;
  throw FormatException('필수 문자열 "$key" 가 없거나 형식이 다릅니다: $v');
}

int? optInt(Map<String, dynamic> j, String key) {
  final v = j[key];
  return v is num ? v.toInt() : null;
}

double optDouble(Map<String, dynamic> j, String key, [double fallback = 0]) {
  final v = j[key];
  return v is num ? v.toDouble() : fallback;
}

String? optStr(Map<String, dynamic> j, String key) {
  final v = j[key];
  return v is String ? v : null;
}

bool optBool(Map<String, dynamic> j, String key) => j[key] == true;

List<String> strList(Map<String, dynamic> j, String key) {
  final v = j[key];
  if (v is List) return v.map((e) => '$e').toList();
  return const [];
}

List<T> objList<T>(Map<String, dynamic> j, String key, T Function(Map<String, dynamic>) f) {
  final v = j[key];
  if (v is! List) return const [];
  return v.whereType<Map<String, dynamic>>().map(f).toList();
}

Map<String, dynamic>? optMap(Map<String, dynamic> j, String key) {
  final v = j[key];
  return v is Map<String, dynamic> ? v : null;
}
