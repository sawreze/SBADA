import 'dart:convert';

import 'package:flutter/material.dart';

import '../map/geo.dart';
import '../models/category.dart';
import '../models/user.dart';
import '../network/session.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/gps_dong_button.dart';
import 'login_screen.dart';
import 'splash_gate.dart';

enum OnboardingMode {
  /// 앱을 처음 설치했을 때(로그인 전) → 고른 값은 기기에 두었다가 로그인 후 서버로 보낸다
  firstRun,

  /// 로그인했지만 아직 선호도가 없을 때
  afterLogin,

  /// 프로필에서 '취향 다시 고르기'
  edit,
}

/// 온보딩: 추천 알고리즘의 '콜드 스타트'를 해결하는 명시적 선호 입력.
///  ① 바꾸고 싶은 물건(카테고리, 1개 이상) → 선호도 가중치 1.0
///  ② 관심 키워드 → 제목에 들어 있으면 +0.3
///  ③ 주로 교환할 가격대 → 내 물건이 없을 때 '가격 적합도'에 쓴다
///  ④ 사는 동네(처음 쓸 때만) → PUT /api/me/region → 홈 왼쪽 위 동네 이름 · 동네지도 중심 · 가까운 물건
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.services, required this.mode, this.initial});

  final AppServices services;
  final OnboardingMode mode;
  final MeProfile? initial;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  static const _presetKeywords = [
    '닌텐도', '아이패드', '에어팟', '캠핑', '레고', '향수', '자전거', '운동화', '가방', '모니터', '책', '보드게임',
  ];

  late int _step;
  final Set<String> _categories = {};
  final List<String> _keywords = [];
  int _band = PriceBand.all.length - 1; // 상관없어요
  final _keywordInput = TextEditingController();
  final _region = TextEditingController();

  /// GPS 로 찾은 동네(칸에 그대로 있으면 '찾은 동네' 표시)
  String? _gpsRegion;
  bool _saving = false;

  bool get _hasWelcome => widget.mode == OnboardingMode.firstRun;
  int get _firstStep => _hasWelcome ? 0 : 1;

  /// 마지막 단계: 처음 쓸 때는 ④ 사는 동네까지, '취향 다시 고르기'는 ③ 가격대까지(동네는 '내 동네 설정'에서 바꾼다)
  int get _lastStep => widget.mode == OnboardingMode.edit ? 3 : 4;

  String get _regionText => _region.text.trim().replaceAll(RegExp(r'\s+'), ' ');

  @override
  void initState() {
    super.initState();
    _step = _firstStep;
    final init = widget.initial;
    if (init != null) {
      _categories.addAll(init.preferences.where((p) => p.source == 'ONBOARDING').map((p) => p.category));
      _keywords.addAll(init.keywords);
      final idx = PriceBand.all.indexWhere((b) => b.min == init.priceMin && b.max == init.priceMax);
      if (idx >= 0) _band = idx;
    }
  }

  @override
  void dispose() {
    _keywordInput.dispose();
    _region.dispose();
    super.dispose();
  }

  bool get _canNext => switch (_step) {
        1 => _categories.isNotEmpty,
        4 => _regionText.isNotEmpty && _regionText.length <= 20,
        _ => true,
      };

  Future<void> _next() async {
    if (_step < _lastStep) {
      setState(() => _step++);
      return;
    }
    await _finish();
  }

  Future<void> _finish() async {
    final s = widget.services;
    final band = PriceBand.all[_band];
    final choice = OnboardingChoice(
      wantedCategories: _categories.toList(),
      keywords: _keywords,
      priceMin: band.min,
      priceMax: band.max,
    );
    setState(() => _saving = true);
    final region = _lastStep == 4 ? _regionText : null;
    await s.session.store.write(Session.onboardingDoneKey, 'true');
    if (!s.session.isLoggedIn) {
      await s.session.store.write(Session.pendingPrefsKey, jsonEncode(choice.toJson()));
      if (region != null) await s.session.store.write(Session.pendingRegionKey, region); // 로그인 뒤 서버에 저장
      if (!mounted) return;
      Navigator.of(context).pushReplacement(MaterialPageRoute<void>(builder: (_) => LoginScreen(services: s)));
      return;
    }
    try {
      await s.auth.savePreferences(choice);
      await s.session.store.remove(Session.pendingPrefsKey);
      if (region != null) {
        await s.auth.changeRegion(region);
        s.itemsChanged.value++; // 홈 · 동네지도가 새 동네로 다시 그린다
      }
      if (!mounted) return;
      if (widget.mode == OnboardingMode.edit) {
        Navigator.of(context).pop(true);
      } else {
        AppFlow.goHome(context, s);
      }
    } catch (e) {
      if (mounted) {
        setState(() => _saving = false);
        showSnack(context, messageOf(e), error: true);
      }
    }
  }

  void _addKeyword(String raw) {
    final k = raw.trim();
    if (k.isEmpty || k.length > 20 || _keywords.contains(k) || _keywords.length >= 10) return;
    setState(() => _keywords.add(k));
    _keywordInput.clear();
  }

  @override
  Widget build(BuildContext context) {
    final total = _lastStep + 1 - _firstStep;
    final progress = (_step - _firstStep + 1) / total;
    return Scaffold(
      appBar: AppBar(
        leading: _step > _firstStep
            ? IconButton(icon: const Icon(Icons.arrow_back), onPressed: () => setState(() => _step--))
            : (widget.mode == OnboardingMode.edit ? const CloseButton() : null),
        automaticallyImplyLeading: false,
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(4),
          child: LinearProgressIndicator(value: progress, minHeight: 4, backgroundColor: Sb.bg),
        ),
      ),
      body: SafeArea(
        child: Column(children: [
          Expanded(
            child: AnimatedSwitcher(
              duration: const Duration(milliseconds: 250),
              child: KeyedSubtree(key: ValueKey(_step), child: _body()),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
            child: FilledButton(
              onPressed: _canNext && !_saving ? _next : null,
              child: _saving
                  ? const SizedBox(
                      width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                  : Text(switch (_step) {
                      0 => '시작하기',
                      1 => _categories.isEmpty ? '1개 이상 골라 주세요' : '다음 (${_categories.length}개 선택)',
                      _ when _step == _lastStep => '완료',
                      _ => '다음',
                    }),
            ),
          ),
        ]),
      ),
    );
  }

  Widget _body() => switch (_step) {
        0 => _welcome(),
        1 => _categoryStep(),
        2 => _keywordStep(),
        3 => _priceStep(),
        _ => _regionStep(),
      };

  Widget _welcome() {
    return Padding(
      padding: EdgeInsets.all(28),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Spacer(),
        Text('🌊', style: TextStyle(fontSize: 64)),
        SizedBox(height: 20),
        Text('안 쓰는 물건,\n돈 없이 바꿔 보세요', style: sbTitle),
        SizedBox(height: 16),
        Text('사진을 찍으면 AI가 중고 시세로 가격을 매겨요.\n비슷한 가격대의 물건을 찾아 바로 바꿀 수 있어요.', style: sbBody),
        SizedBox(height: 28),
        _Feature(icon: '📸', title: '사진 한 장으로 AI 가격 분석', body: 'Gemma가 물건을 알아보고 시세를 추정해요'),
        _Feature(icon: '⚖️', title: '비슷한 가격대 자동 매칭', body: '서로 원하는 물건일수록 위에 보여요'),
        _Feature(icon: '🔔', title: '수락하면 교환 완료', body: '요청이 오면 알림으로 바로 알려 드려요'),
        Spacer(flex: 2),
      ]),
    );
  }

  Widget _categoryStep() {
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text('어떤 물건으로\n바꾸고 싶나요?', style: sbTitle),
      const SizedBox(height: 8),
      Text('고른 종류의 물건을 먼저 추천해 드려요. 여러 개 골라도 돼요.', style: sbBody),
      const SizedBox(height: 20),
      GridView.count(
        crossAxisCount: 3,
        shrinkWrap: true,
        physics: const NeverScrollableScrollPhysics(),
        mainAxisSpacing: 10,
        crossAxisSpacing: 10,
        childAspectRatio: 1.05,
        children: [
          for (final c in CategoryInfo.all)
            _CategoryTile(
              info: c,
              selected: _categories.contains(c.code),
              onTap: () => setState(() {
                if (!_categories.remove(c.code)) _categories.add(c.code);
              }),
            ),
        ],
      ),
    ]);
  }

  Widget _keywordStep() {
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text('관심 있는 물건이\n따로 있나요?', style: sbTitle),
      const SizedBox(height: 8),
      Text('제목에 이 단어가 들어간 물건은 추천 점수가 올라가요. 건너뛰어도 돼요.', style: sbBody),
      const SizedBox(height: 20),
      TextField(
        controller: _keywordInput,
        textInputAction: TextInputAction.done,
        onSubmitted: _addKeyword,
        decoration: sbInput('직접 입력',
            hint: '예) 스위치, 캠핑의자',
            suffix: IconButton(icon: const Icon(Icons.add), onPressed: () => _addKeyword(_keywordInput.text))),
      ),
      const SizedBox(height: 16),
      Wrap(spacing: 8, runSpacing: 8, children: [
        for (final k in {..._presetKeywords, ..._keywords})
          FilterChip(
            label: Text(k),
            selected: _keywords.contains(k),
            onSelected: (on) => setState(() {
              if (on) {
                if (_keywords.length < 10) _keywords.add(k);
              } else {
                _keywords.remove(k);
              }
            }),
            showCheckmark: false,
            selectedColor: Sb.blueSoft,
            labelStyle: TextStyle(
                color: _keywords.contains(k) ? Sb.blue : Sb.text2, fontWeight: FontWeight.w600),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            side: BorderSide(color: _keywords.contains(k) ? Sb.mintText : Sb.line),
          ),
      ]),
    ]);
  }

  Widget _priceStep() {
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text('주로 어느 가격대로\n교환하고 싶나요?', style: sbTitle),
      const SizedBox(height: 8),
      Text('아직 올린 물건이 없을 때 이 가격대의 물건을 먼저 보여 드려요.', style: sbBody),
      const SizedBox(height: 20),
      for (var i = 0; i < PriceBand.all.length; i++)
        Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: InkWell(
            borderRadius: BorderRadius.circular(16),
            onTap: () => setState(() => _band = i),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 18),
              decoration: BoxDecoration(
                color: _band == i ? Sb.blueSoft : Sb.card,
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: _band == i ? Sb.mintText : Sb.line, width: _band == i ? 1.5 : 1),
              ),
              child: Row(children: [
                Expanded(
                  child: Text(PriceBand.all[i].label,
                      style: TextStyle(
                          fontSize: 16, fontWeight: FontWeight.w600, color: _band == i ? Sb.blue : Sb.navy)),
                ),
                if (_band == i) Icon(Icons.check_circle, color: Sb.mintText),
              ]),
            ),
          ),
        ),
    ]);
  }

  /// ④ 사는 동네: '현재 위치로 동네 찾기'(GPS) → 찾은 동네가 아래 칸에 채워진다.
  /// GPS 를 못 쓰면 직접 입력: 도시 · 구 · 동을 적으면 맞는 동네 후보가 뜬다(눌러서 고르기). 목록에 없어도 적은 그대로 저장
  Widget _regionStep() {
    final q = _regionText;
    final words = q.split(' ').where((w) => w.isNotEmpty).toList();
    final all = knownRegions();
    final hits = q.isEmpty || q == _gpsRegion
        ? const <String>[]
        : all.where((r) => words.every((w) => r.contains(w))).take(8).toList();
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text('어느 동네에\n사세요?', style: sbTitle),
      const SizedBox(height: 8),
      Text('지금 있는 곳으로 동네를 찾아 드려요. 홈 왼쪽 위에 내 동네가 뜨고, 가까운 이웃의 물건부터 보여 드려요.', style: sbBody),
      const SizedBox(height: 20),
      GpsDongButton(
        services: widget.services,
        found: q.isNotEmpty && q == _gpsRegion ? q : null,
        onFound: (d) => setState(() {
          _gpsRegion = d.region;
          _region.text = d.region;
          _region.selection = TextSelection.collapsed(offset: d.region.length);
          FocusScope.of(context).unfocus();
        }),
      ),
      const SizedBox(height: 20),
      Row(children: [
        Expanded(child: Divider(color: Sb.line)),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10),
          child: Text('또는 직접 입력', style: TextStyle(fontSize: 13, color: Sb.text2)),
        ),
        Expanded(child: Divider(color: Sb.line)),
      ]),
      const SizedBox(height: 12),
      TextField(
        key: const ValueKey('onboarding-region'),
        controller: _region,
        maxLength: 20,
        textInputAction: TextInputAction.done,
        onChanged: (_) => setState(() {}),
        onSubmitted: (_) {
          if (_canNext) _next();
        },
        decoration: sbInput(null, hint: '예) 부산 해운대구 우동, 수원시 인계동, 마포구 망원동'),
      ),
      if (hits.isNotEmpty) ...[
        const SizedBox(height: 4),
        for (final r in hits)
          ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 4),
            leading: Icon(Icons.place_outlined, color: Sb.mintText),
            title: Text(r, style: TextStyle(fontSize: 16, color: Sb.navy, fontWeight: r == q ? FontWeight.w800 : FontWeight.w500)),
            trailing: r == q ? Icon(Icons.check_circle, color: Sb.mintText) : null,
            onTap: () => setState(() {
              _region.text = r;
              _region.selection = TextSelection.collapsed(offset: r.length);
            }),
          ),
      ] else if (q.isNotEmpty && q == _gpsRegion)
        const SizedBox.shrink() // GPS 로 찾은 동네는 위에 '찾은 동네'로 이미 보인다
      else if (q.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text('목록에 없는 동네예요. 적은 그대로 \'$q\'(으)로 저장해요.', style: sbCaption),
        )
      else ...[
        const SizedBox(height: 8),
        Text('많이 찾는 동네', style: TextStyle(fontSize: 13, color: Sb.text2)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final r in const ['서울 강남구 역삼동', '마포구 망원동', '경기 수원시 인계동', '부산 해운대구 우동', '대구 수성구 범어동', '제주 제주시 노형동'])
            ActionChip(
              label: Text(r),
              onPressed: () => setState(() {
                _region.text = r;
                _region.selection = TextSelection.collapsed(offset: r.length);
              }),
            ),
        ]),
      ],
    ]);
  }
}

class _Feature extends StatelessWidget {
  const _Feature({required this.icon, required this.title, required this.body});

  final String icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(children: [
        Container(
          width: 48,
          height: 48,
          alignment: Alignment.center,
          decoration: BoxDecoration(color: Sb.bg, borderRadius: BorderRadius.circular(14)),
          child: Text(icon, style: const TextStyle(fontSize: 24)),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: TextStyle(fontWeight: FontWeight.w700, fontSize: 15, color: Sb.navy)),
            Text(body, style: sbCaption),
          ]),
        ),
      ]),
    );
  }
}

class _CategoryTile extends StatelessWidget {
  const _CategoryTile({required this.info, required this.selected, required this.onTap});

  final CategoryInfo info;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(18),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        decoration: BoxDecoration(
          color: selected ? Sb.blueSoft : Sb.bg,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: selected ? Sb.mintText : Colors.transparent, width: 1.5),
        ),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Text(info.emoji, style: const TextStyle(fontSize: 30)),
          const SizedBox(height: 6),
          Text(info.label,
              textAlign: TextAlign.center,
              maxLines: 2,
              style: TextStyle(
                  fontSize: 12.5, fontWeight: FontWeight.w600, color: selected ? Sb.blue : Sb.text2)),
        ]),
      ),
    );
  }
}
