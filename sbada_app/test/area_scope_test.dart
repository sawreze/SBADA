import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/util/area_scope.dart';

/// 홈 왼쪽 위 범위: 동네 이름으로 '내 동네 · 시(구) · 도 · 전국' 고를 거리를 만든다
void main() {
  test('서울 마포구 망원동 → 망원동 · 마포구 · 서울 전체 · 전국', () {
    final o = areaOptions('서울 마포구 망원동');
    expect(o.map((e) => e.label), ['망원동', '마포구', '서울 전체', '전국']);
    expect(o.map((e) => e.area), ['서울 마포구 망원동', '서울 마포구', '서울', null]);
  });

  test('경기 수원시 권선동 → 권선동 · 수원시 · 경기 전체 · 전국', () {
    expect(areaOptions('경기 수원시 권선동').map((e) => e.label), ['권선동', '수원시', '경기 전체', '전국']);
  });

  test('시도 없이 저장된 동네 · 세종처럼 시군구가 없는 곳', () {
    expect(areaOptions('마포구 망원동').map((e) => e.area), ['마포구 망원동', '마포구', null]);
    expect(areaOptions('세종 보람동').map((e) => e.label), ['보람동', '세종 전체', '전국']);
    expect(areaOptions('').map((e) => e.label), ['전국']);
  });

  test('고른 단계가 없는 동네면 한 단계 넓게', () {
    expect(areaOptionFor('마포구 망원동', AreaLevel.province).level, AreaLevel.nationwide);
    expect(areaOptionFor('세종 보람동', AreaLevel.city).level, AreaLevel.province);
    expect(areaOptionFor('서울 마포구 망원동', AreaLevel.city).area, '서울 마포구');
  });
}
