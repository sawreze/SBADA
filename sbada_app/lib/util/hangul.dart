/// 검색창 추천 목록에서 '어디가 맞았는지' 굵게 칠하려고 쓰는 한글 비교(서버 HangulSearch 와 같은 규칙).
///  - 대소문자 · 띄어쓰기 무시
///  - 검색어의 자음(ㄱ~ㅎ)은 그 자리 글자의 첫소리와 비교(초성 검색: ㅅㅁㅌㅍ → 스마트폰)
library;

const _choseong = ['ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];

String? _first(String ch) {
  final c = ch.codeUnitAt(0);
  if (c < 0xAC00 || c > 0xD7A3) return null;
  return _choseong[(c - 0xAC00) ~/ 588];
}

bool _isConsonant(String ch) {
  final c = ch.codeUnitAt(0);
  return c >= 0x3131 && c <= 0x314E;
}

bool _same(String t, String q) => t == q || (_isConsonant(q) && _first(t) == q);

/// [text] 안에서 [query] 가 맞는 곳(원래 글자 기준 시작 · 끝). 없으면 null.
/// 띄어쓰기를 건너뛰며 비교하므로 '블루투스헤드폰' 으로 '블루투스 헤드폰' 을 찾을 수 있다.
(int, int)? hangulMatch(String text, String query) {
  final q = query.replaceAll(RegExp(r'\s+'), '').toLowerCase();
  if (q.isEmpty) return null;
  final lower = text.toLowerCase();
  // 띄어쓰기를 뺀 글자들과 그 글자의 원래 위치
  final chars = <String>[];
  final pos = <int>[];
  for (var i = 0; i < lower.length; i++) {
    if (lower[i].trim().isEmpty) continue;
    chars.add(lower[i]);
    pos.add(i);
  }
  for (var s = 0; s + q.length <= chars.length; s++) {
    var k = 0;
    while (k < q.length && _same(chars[s + k], q[k])) {
      k++;
    }
    if (k == q.length) return (pos[s], pos[s + q.length - 1] + 1);
  }
  return null;
}
