import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/util/hangul.dart';

/// 검색 추천 목록의 굵은 글씨 위치(서버 HangulSearch 와 같은 규칙)
void main() {
  test('이름 · 띄어쓰기 무시 · 대소문자', () {
    expect(hangulMatch('블루투스 헤드폰', '헤드폰'), (5, 8));
    expect(hangulMatch('블루투스 헤드폰', '스헤'), (3, 6));
    expect(hangulMatch('27인치 QHD 모니터', 'qhd'), (5, 8));
    expect(hangulMatch('블루투스 헤드폰', '이어폰'), isNull);
  });

  test('초성 검색', () {
    expect(hangulMatch('스마트폰', 'ㅅㅁㅌㅍ'), (0, 4));
    expect(hangulMatch('마포구 망원동', 'ㅁㅇㄷ'), (4, 7));
    expect(hangulMatch('헤드폰', '헤ㄷ'), (0, 2));
    expect(hangulMatch('헤드폰', ''), isNull);
  });
}
