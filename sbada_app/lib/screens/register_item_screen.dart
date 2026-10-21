import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../models/ai_analysis.dart';
import '../models/category.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../util/format.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import 'match_screen.dart';

/// 물건 올리기: ① 사진 찍기 → ② AI(Gemma)가 가격 분석 → ③ 확인·수정 → ④ 등록 → 비슷한 가격대 매칭
class RegisterItemScreen extends StatefulWidget {
  const RegisterItemScreen({super.key, required this.services, this.picker});

  final AppServices services;

  /// 테스트에서 가짜 사진을 넣을 수 있게 주입받는다
  final ImagePicker? picker;

  @override
  State<RegisterItemScreen> createState() => _RegisterItemScreenState();
}

enum _Step { photo, analyzing, result }

class _Photo {
  _Photo(this.bytes, this.name);

  final Uint8List bytes;
  final String name;
  int? uploadedId;
}

class _RegisterItemScreenState extends State<RegisterItemScreen> {
  late final ImagePicker _picker = widget.picker ?? ImagePicker();
  final List<_Photo> _photos = [];
  _Step _step = _Step.photo;
  AiAnalysis? _analysis;
  String? _category;
  String? _condition;
  final Set<String> _wishes = {};
  final _title = TextEditingController();
  final _description = TextEditingController();

  /// 정든 물건에게 보내는 짧은 메시지(선택, 60자) — 목록에서 가격 아래 "…" 로 굵게 보인다
  final _farewell = TextEditingController();
  bool _saving = false;
  int _analyzeStage = 0;
  Timer? _stageTimer;

  /// AI 가격의 ±30%(0.77~1.3배) 안에 있는 남의 교환 가능 물건 수(디자인 시안의 '이웃 물건 N개와 매칭돼요')
  int? _nearbyMatches;

  Future<void> _countMatches(int price) async {
    try {
      final me = widget.services.session.user?.id;
      final list = await widget.services.items.latest();
      final n = list
          .where((i) => i.isAvailable && i.owner.id != me && i.price >= price * 0.77 && i.price <= price * 1.3)
          .length;
      if (mounted) setState(() => _nearbyMatches = n);
    } catch (_) {
      // 개수는 안내용이라 실패하면 줄만 숨긴다
      if (mounted) setState(() => _nearbyMatches = null);
    }
  }

  bool get _hintsChanged =>
      _analysis != null && (_analysis!.category != _category || _analysis!.condition != _condition);

  @override
  void dispose() {
    _stageTimer?.cancel();
    _title.dispose();
    _description.dispose();
    _farewell.dispose();
    super.dispose();
  }

  Future<void> _pick(ImageSource source) async {
    if (_photos.length >= 5) return;
    try {
      final file = await _picker.pickImage(source: source, maxWidth: 1280, maxHeight: 1280, imageQuality: 85);
      if (file == null) return; // 사용자가 취소
      final bytes = await file.readAsBytes();
      if (bytes.length > 5 * 1024 * 1024) {
        if (mounted) showSnack(context, '사진이 너무 커요(5MB 이하).', error: true);
        return;
      }
      setState(() => _photos.add(_Photo(bytes, file.name.isEmpty ? 'photo.jpg' : file.name)));
    } catch (e) {
      if (mounted) showSnack(context, '사진을 가져오지 못했어요: $e', error: true);
    }
  }

  /// 사진 업로드 후 AI 분석. 다시 분석할 때는 사용자가 고른 카테고리·상태를 힌트로 보낸다.
  Future<void> _analyze({bool withHints = false}) async {
    setState(() {
      _step = _Step.analyzing;
      _analyzeStage = 0;
    });
    _stageTimer?.cancel();
    _stageTimer = Timer.periodic(const Duration(milliseconds: 1400), (_) {
      if (mounted && _analyzeStage < 3) setState(() => _analyzeStage++);
    });
    try {
      for (final p in _photos) {
        p.uploadedId ??= await widget.services.items.uploadImage(p.bytes, p.name);
      }
      final result = await widget.services.items.analyze(
        _photos.first.uploadedId!,
        title: withHints && _title.text.trim().isNotEmpty ? _title.text.trim() : null,
        category: withHints ? _category : null,
        condition: withHints ? _condition : null,
      );
      if (!mounted) return;
      setState(() {
        _analysis = result;
        _category = result.category;
        _condition = result.condition;
        if (!withHints || _title.text.trim().isEmpty) _title.text = result.suggestedTitle;
        _step = _Step.result;
      });
      unawaited(_countMatches(result.price));
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _step = _analysis == null ? _Step.photo : _Step.result);
      showSnack(context, e.message, error: true);
    } finally {
      _stageTimer?.cancel();
    }
  }

  Future<void> _submit() async {
    final analysis = _analysis;
    if (analysis == null || _saving) return;
    if (_title.text.trim().isEmpty) {
      showSnack(context, '물건 이름을 입력해 주세요.', error: true);
      return;
    }
    if (_hintsChanged) {
      await _analyze(withHints: true); // 바뀐 정보로 가격을 다시 받는다
      if (!mounted || _hintsChanged) return;
    }
    setState(() => _saving = true);
    try {
      // 분석 뒤에 더 고른 사진도 올린다(이미 올린 사진은 번호를 다시 쓴다)
      for (final p in _photos) {
        p.uploadedId ??= await widget.services.items.uploadImage(p.bytes, p.name);
      }
      final item = await widget.services.items.create(
        title: _title.text.trim(),
        description: _description.text.trim().isEmpty ? null : _description.text.trim(),
        category: _category!,
        condition: _condition!,
        imageIds: _photos.map((p) => p.uploadedId!).toList(),
        analysisId: _analysis!.analysisId,
        wishCategories: _wishes.toList(),
        farewellMessage: _farewell.text.trim().isEmpty ? null : _farewell.text.trim(),
      );
      if (!mounted) return;
      widget.services.itemsChanged.value++;
      await Navigator.of(context).pushReplacement(MaterialPageRoute<bool>(
        builder: (_) => MatchScreen(services: widget.services, item: item, justCreated: true),
      ));
    } on ApiException catch (e) {
      if (mounted) {
        setState(() => _saving = false);
        showSnack(context, e.message, error: true);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('물건 올리기')),
      body: SafeArea(
        child: switch (_step) {
          _Step.photo => _photoStep(),
          _Step.analyzing => _analyzingStep(),
          _Step.result => _resultStep(),
        },
      ),
    );
  }

  Widget _photoStep() {
    return Column(children: [
      Expanded(
        child: ListView(padding: const EdgeInsets.all(20), children: [
          Text('바꾸고 싶은 물건을\n찍어 주세요', style: sbTitle),
          const SizedBox(height: 8),
          Text('첫 번째 사진으로 AI가 물건을 알아보고 가격을 매겨요. 최대 5장.', style: sbBody),
          const SizedBox(height: 20),
          Row(children: [
            Expanded(
              child: _bigButton(Icons.photo_camera_rounded, '사진 찍기', () => _pick(ImageSource.camera), primary: true),
            ),
            const SizedBox(width: 12),
            Expanded(child: _bigButton(Icons.photo_library_outlined, '앨범에서', () => _pick(ImageSource.gallery))),
          ]),
          const SizedBox(height: 20),
          if (_photos.isNotEmpty)
            SizedBox(
              height: 104,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: _photos.length,
                separatorBuilder: (context, i) => const SizedBox(width: 10),
                itemBuilder: (context, i) => Stack(children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(14),
                    child: Image.memory(_photos[i].bytes, width: 104, height: 104, fit: BoxFit.cover),
                  ),
                  if (i == 0)
                    const Positioned(left: 6, top: 6, child: Pill('대표', color: Sb.blue, textColor: Sb.onAccent)),
                  Positioned(
                    right: 2,
                    top: 2,
                    child: IconButton(
                      visualDensity: VisualDensity.compact,
                      style: IconButton.styleFrom(backgroundColor: Colors.black45),
                      icon: const Icon(Icons.close, size: 16, color: Colors.white),
                      onPressed: () => setState(() => _photos.removeAt(i)),
                    ),
                  ),
                ]),
              ),
            ),
        ]),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
        child: FilledButton.icon(
          onPressed: _photos.isEmpty ? null : () => _analyze(),
          icon: const Icon(Icons.auto_awesome),
          label: const Text('AI 가격 분석하기'),
        ),
      ),
    ]);
  }

  Widget _bigButton(IconData icon, String label, VoidCallback onTap, {bool primary = false}) {
    return InkWell(
      onTap: onTap,
      borderRadius: Sb.r,
      child: Container(
        height: 120,
        decoration: BoxDecoration(
          color: primary ? Sb.blueSoft : Sb.bg,
          borderRadius: Sb.r,
          border: Border.all(color: primary ? Sb.mintText : Colors.transparent),
        ),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(icon, size: 36, color: primary ? Sb.mintText : Sb.text2),
          const SizedBox(height: 8),
          Text(label, style: TextStyle(fontWeight: FontWeight.w700, color: primary ? Sb.mintText : Sb.text2)),
        ]),
      ),
    );
  }

  Widget _analyzingStep() {
    const stages = ['사진 올리는 중', 'Gemma가 물건을 알아보는 중', '중고 시세를 추정하는 중', '앱 안의 비슷한 물건 시세로 보정하는 중'];
    return Padding(
      padding: const EdgeInsets.all(28),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Spacer(),
        ClipRRect(
          borderRadius: Sb.r,
          child: Image.memory(_photos.first.bytes, width: 120, height: 120, fit: BoxFit.cover),
        ),
        const SizedBox(height: 24),
        Text('AI가 가격을\n분석하고 있어요', style: sbTitle),
        const SizedBox(height: 20),
        for (var i = 0; i < stages.length; i++)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(children: [
              SizedBox(
                width: 22,
                height: 22,
                child: i < _analyzeStage
                    ? Icon(Icons.check_circle, color: Sb.green, size: 22)
                    : i == _analyzeStage
                        ? const CircularProgressIndicator(strokeWidth: 2.5)
                        : Icon(Icons.circle_outlined, color: Sb.line, size: 22),
              ),
              const SizedBox(width: 12),
              Text(stages[i],
                  style: TextStyle(fontSize: 15, color: i <= _analyzeStage ? Sb.navy : Sb.text3)),
            ]),
          ),
        const Spacer(flex: 2),
      ]),
    );
  }

  /// 결과 단계(디자인 시안 '물건 올리기'): 사진 칸 → 물건 이름 → 상태 → AI 가격 카드 → (카테고리 · 설명 · 바꾸고 싶은 종류)
  Widget _resultStep() {
    final a = _analysis!;
    return Column(children: [
      Expanded(
        child: ListView(padding: const EdgeInsets.fromLTRB(16, 4, 16, 20), children: [
          _photoStrip(),
          const SizedBox(height: 16),
          _fieldLabel('물건 이름'),
          TextField(controller: _title, maxLength: 60, decoration: sbInput(null)),
          const SizedBox(height: 4),
          _fieldLabel('상태'),
          _conditionGrid(),
          const SizedBox(height: 16),
          _aiCard(a),
          if (_hintsChanged) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: () => _analyze(withHints: true),
              icon: const Icon(Icons.refresh),
              label: const Text('바뀐 정보로 AI 가격 다시 받기'),
            ),
          ],
          const SizedBox(height: 8),
          Text('가격은 AI가 정해요. 카테고리나 상태를 바꾸면 다시 분석해서 가격이 바뀌어요.', style: sbCaption),
          const SizedBox(height: 20),
          _fieldLabel('카테고리'),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final c in CategoryInfo.all)
              ChoiceChip(
                label: Text(c.label),
                selected: _category == c.code,
                showCheckmark: false,
                onSelected: (_) => setState(() => _category = c.code),
              ),
          ]),
          const SizedBox(height: 16),
          TextField(
            controller: _description,
            maxLines: 4,
            maxLength: 1000,
            decoration: sbInput('설명 (선택)', hint: '사용 기간, 구성품, 흠집 등을 적어 주세요'),
          ),
          const SizedBox(height: 8),
          _fieldLabel('정든 물건에게 한마디 (선택)'),
          TextField(
            controller: _farewell,
            maxLength: 60,
            textInputAction: TextInputAction.done,
            decoration: sbInput(null, hint: '예) 3년 동안 고마웠어, 새 주인이랑 행복해'),
          ),
          Text('목록에서 가격 아래에 큰따옴표와 함께 보여요.', style: sbCaption),
          const SizedBox(height: 8),
          _fieldLabel('이 물건과 바꾸고 싶은 종류 (선택)'),
          Text('고르면 그 물건을 가진 사람에게 우선 추천돼요(상호 매칭).', style: sbCaption),
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final c in CategoryInfo.all)
              FilterChip(
                label: Text(c.label),
                selected: _wishes.contains(c.code),
                showCheckmark: false,
                onSelected: (on) => setState(() {
                  if (on && _wishes.length < 5) {
                    _wishes.add(c.code);
                  } else {
                    _wishes.remove(c.code);
                  }
                }),
              ),
          ]),
        ]),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
        child: FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
              : Text(_hintsChanged ? '다시 분석하고 올리기' : '이 가격으로 올리기'),
        ),
      ),
    ]);
  }

  Widget _fieldLabel(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(text, style: TextStyle(fontSize: 13, color: Sb.text2)),
      );

  /// 사진 칸: [+ n/5 점선 버튼] [사진] [사진] … 첫 장(대표 · AI가 본 사진)은 뺄 수 없다.
  Widget _photoStrip() {
    return SizedBox(
      height: 76,
      child: ListView(scrollDirection: Axis.horizontal, children: [
        if (_photos.length < 5)
          Padding(
            padding: const EdgeInsets.only(right: 10),
            child: Semantics(
              button: true,
              label: '사진 추가',
              child: InkWell(
                borderRadius: BorderRadius.circular(14),
                onTap: _pickSource,
                child: CustomPaint(
                  painter: _DashedBorder(color: Sb.text3, radius: 14),
                  child: SizedBox(
                    width: 76,
                    height: 76,
                    child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Icon(Icons.add_a_photo_outlined, color: Sb.text2, size: 22),
                      const SizedBox(height: 2),
                      Text('${_photos.length}/5', style: TextStyle(fontSize: 12, color: Sb.text2)),
                    ]),
                  ),
                ),
              ),
            ),
          ),
        for (var i = 0; i < _photos.length; i++)
          Padding(
            padding: const EdgeInsets.only(right: 10),
            child: Stack(children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(14),
                child: Image.memory(_photos[i].bytes, width: 76, height: 76, fit: BoxFit.cover),
              ),
              if (i == 0) const Positioned(left: 4, top: 4, child: Pill('대표', color: Sb.blue, textColor: Sb.onAccent)),
              if (i > 0)
                Positioned(
                  right: 0,
                  top: 0,
                  child: IconButton(
                    tooltip: '사진 빼기',
                    visualDensity: VisualDensity.compact,
                    style: IconButton.styleFrom(backgroundColor: Colors.black45),
                    icon: const Icon(Icons.close, size: 14, color: Colors.white),
                    onPressed: () => setState(() => _photos.removeAt(i)),
                  ),
                ),
            ]),
          ),
      ]),
    );
  }

  Future<void> _pickSource() async {
    final source = await showModalBottomSheet<ImageSource>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          ListTile(
            leading: const Icon(Icons.photo_camera_rounded),
            title: const Text('사진 찍기'),
            onTap: () => Navigator.pop(context, ImageSource.camera),
          ),
          ListTile(
            leading: const Icon(Icons.photo_library_outlined),
            title: const Text('앨범에서 고르기'),
            onTap: () => Navigator.pop(context, ImageSource.gallery),
          ),
        ]),
      ),
    );
    if (source != null) await _pick(source);
  }

  /// 상태: 3칸 격자 라디오(디자인 시안). 고른 칸은 민트 테두리 + 옅은 민트 바탕
  Widget _conditionGrid() {
    return LayoutBuilder(builder: (context, c) {
      final w = (c.maxWidth - 16) / 3;
      return Wrap(spacing: 8, runSpacing: 8, children: [
        for (final info in ConditionInfo.all)
          Semantics(
            selected: _condition == info.code,
            button: true,
            child: Tooltip(
              message: info.description,
              child: InkWell(
                borderRadius: BorderRadius.circular(12),
                onTap: () => setState(() => _condition = info.code),
                child: Container(
                  width: w,
                  height: 44,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: _condition == info.code ? Sb.blueSoft : Sb.bg,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                        color: _condition == info.code ? Sb.mintText : Sb.line,
                        width: _condition == info.code ? 1.5 : 1),
                  ),
                  child: Text(info.label,
                      maxLines: 1,
                      style: TextStyle(
                          fontSize: 14,
                          fontWeight: _condition == info.code ? FontWeight.w700 : FontWeight.w600,
                          color: _condition == info.code ? Sb.navy : Sb.text2)),
                ),
              ),
            ),
          ),
      ]);
    });
  }

  /// AI 가격 카드(디자인 시안): 옅은 민트 바탕 · 민트 테두리, 아래 줄에 같은 가격대 이웃 물건 수
  Widget _aiCard(AiAnalysis a) {
    final confidence = a.confidence >= 0.75 ? '높음' : a.confidence >= 0.5 ? '보통' : '낮음';
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Sb.blue.withValues(alpha: Sb.isDark ? 0.10 : 0.16),
        borderRadius: Sb.r,
        border: Border.all(color: Sb.mintText.withValues(alpha: 0.35)),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(Icons.auto_awesome, color: Sb.mintText, size: 16),
          const SizedBox(width: 6),
          Expanded(
            child: Text('${a.providerLabel} · ${a.categoryLabel}',
                style: TextStyle(color: Sb.mintText, fontSize: 13, fontWeight: FontWeight.w700)),
          ),
        ]),
        const SizedBox(height: 8),
        Text(formatWon(a.price), style: TextStyle(color: Sb.navy, fontSize: 28, fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text(
            '적정 범위 ${formatWon(a.priceMin)} ~ ${formatWon(a.priceMax)} · '
            '신뢰도 $confidence(${(a.confidence * 100).round()}%)',
            style: TextStyle(color: Sb.text2, fontSize: 13)),
        const SizedBox(height: 8),
        Text(a.reason, style: TextStyle(color: Sb.text2, fontSize: 14, height: 1.5)),
        if (a.marketSamples >= 3 && a.marketMedian != null) ...[
          const SizedBox(height: 6),
          Text(
            '앱 안 시세 보정: 비슷한 물건 ${a.marketSamples}개 중앙값 ${formatWon(a.marketMedian!)}을 '
            '${(a.marketWeight * 100).round()}% 반영',
            style: TextStyle(color: Sb.text3, fontSize: 12),
          ),
        ],
        if (a.tags.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final t in a.tags) Pill('#$t', color: Sb.chip, textColor: Sb.navy),
          ]),
        ],
        const SizedBox(height: 12),
        PriceRangeBar(price: a.price, min: a.priceMin, max: a.priceMax),
        if (_nearbyMatches != null) ...[
          const SizedBox(height: 10),
          Divider(height: 1, color: Sb.mintText.withValues(alpha: 0.25)),
          const SizedBox(height: 10),
          Text.rich(TextSpan(style: TextStyle(fontSize: 13, color: Sb.navy), children: [
            const TextSpan(text: '이 가격대(±30%) 이웃 물건 '),
            TextSpan(
                text: '$_nearbyMatches개',
                style: TextStyle(color: Sb.mintText, fontWeight: FontWeight.w800)),
            const TextSpan(text: '와 매칭돼요'),
          ])),
        ],
      ]),
    );
  }
}

/// 둥근 점선 테두리(사진 추가 칸)
class _DashedBorder extends CustomPainter {
  const _DashedBorder({required this.color, required this.radius});

  final Color color;
  final double radius;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final path = Path()
      ..addRRect(RRect.fromRectAndRadius(Offset.zero & size, Radius.circular(radius)).deflate(0.75));
    for (final metric in path.computeMetrics()) {
      for (double d = 0; d < metric.length; d += 9) {
        canvas.drawPath(metric.extractPath(d, d + 5), paint);
      }
    }
  }

  @override
  bool shouldRepaint(_DashedBorder old) => old.color != color || old.radius != radius;
}
