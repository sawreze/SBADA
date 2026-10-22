/// 홈 왼쪽 위 '범위' — 내 동네 · 시(구) · 도 · 전국 중에서 고른다. 고른 범위 안의 물건만 홈 목록 · AI 추천에 뜬다.
/// 서버에는 area 로 동네 이름의 앞부분을 보낸다(GET /api/feed · /api/items · /api/items/count 의 area).
///  - '서울 마포구 망원동' → 망원동(서울 마포구 망원동) · 마포구(서울 마포구) · 서울 전체(서울) · 전국
///  - '경기 수원시 권선동' → 권선동 · 수원시(경기 수원시) · 경기 전체(경기) · 전국
///  - '마포구 망원동'(시도 없이 저장된 시연 계정) → 망원동 · 마포구 · 전국
library;

enum AreaLevel { dong, city, province, nationwide }

/// 고를 수 있는 범위 하나. area = 서버에 보낼 값(null = 전국), label = 화면 이름, kind = '내 동네' · '시 · 구' · '도' · '전국'
typedef AreaOption = ({AreaLevel level, String? area, String label, String kind});

const _sido = {
  '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종', '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주',
};

List<AreaOption> areaOptions(String region) {
  final w = region.trim().split(RegExp(r'\s+')).where((e) => e.isNotEmpty).toList();
  final options = <AreaOption>[];
  if (w.isNotEmpty) {
    options.add((level: AreaLevel.dong, area: w.join(' '), label: w.last, kind: '내 동네'));
    final hasSido = _sido.contains(w.first);
    // 시 · 구: 시도 + 시군구(3단어 이상) 또는 시도 없이 '마포구 망원동'(2단어)
    if (hasSido && w.length >= 3) {
      options.add((level: AreaLevel.city, area: w.take(w.length - 1).join(' '), label: w[w.length - 2], kind: '시 · 구'));
    } else if (!hasSido && w.length >= 2) {
      options.add((level: AreaLevel.city, area: w.take(w.length - 1).join(' '), label: w[w.length - 2], kind: '시 · 구'));
    }
    if (hasSido && w.length >= 2) {
      options.add((level: AreaLevel.province, area: w.first, label: '${w.first} 전체', kind: '도'));
    }
  }
  options.add((level: AreaLevel.nationwide, area: null, label: '전국', kind: '전국'));
  return options;
}

/// 고른 단계가 이 동네에 없으면(예: 시도 없는 동네에서 '도') 한 단계 넓은 쪽으로
AreaOption areaOptionFor(String region, AreaLevel level) {
  final options = areaOptions(region);
  for (final o in options) {
    if (o.level.index >= level.index) return o;
  }
  return options.last;
}
