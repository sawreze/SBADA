import 'package:flutter/material.dart';

import '../map/geo.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/gps_dong_button.dart';

/// 내 동네 설정(어디에 사는지). PUT /api/me/region
///  - 홈 위쪽 동네 이름 · 동네지도 중심 · 내 물건 위치(동네 중심 근처)가 이 값으로 바뀐다.
///  - 직접 입력하거나, 동네지도에 있는 동네 칩을 눌러 고른다. 20자 이하(가입 때와 같은 규칙).
///  - [second] = true 면 '동네 추가'(내 동네는 최대 2개, PUT /api/me/second-region). 추가한 동네는
///    홈 왼쪽 위 동네 이름을 눌러 지금 동네와 바꾼다(POST /api/me/region/swap).
class RegionScreen extends StatefulWidget {
  const RegionScreen({super.key, required this.services, this.second = false});

  final AppServices services;
  final bool second;

  @override
  State<RegionScreen> createState() => _RegionScreenState();
}

class _RegionScreenState extends State<RegionScreen> {
  /// GPS 로 찾은 동네(칸에 그대로 있으면 '찾은 동네' 표시)
  String? _gpsRegion;

  late final TextEditingController _input = TextEditingController(
      text: (widget.second ? widget.services.session.user?.secondRegion : widget.services.session.user?.region) ?? '');
  bool _saving = false;
  String? _error;

  /// 동네지도에 있는 동네(dongCenters) → '구 동' 으로 보여 준다
  static String _withGu(String dong) => dong == '연희동' ? '서대문구 $dong' : '마포구 $dong';

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final region = _input.text.trim();
    if (region.isEmpty) {
      setState(() => _error = '동네를 입력해 주세요. 예) 마포구 망원동');
      return;
    }
    if (region.length > 20) {
      setState(() => _error = '동네 이름은 20자 이하로 적어 주세요.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      if (widget.second) {
        await widget.services.auth.setSecondRegion(region);
        if (!mounted) return;
        // 어디서 바꾸는지 알려 준다(사용자 요청)
        showSnack(context, '\'$region\'을(를) 추가했어요. 홈 왼쪽 위 동네 이름을 누르면 이 동네로 바꿀 수 있어요.');
        Navigator.of(context).pop(true);
        return;
      }
      await widget.services.auth.changeRegion(region);
      widget.services.itemsChanged.value++; // 홈 · 동네지도가 새 동네로 다시 그린다
      if (!mounted) return;
      showSnack(context, '내 동네를 \'$region\'(으)로 바꿨어요.');
      Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// 추가한 동네 지우기(동네 추가 화면에서만)
  Future<void> _delete() async {
    setState(() => _saving = true);
    try {
      await widget.services.auth.deleteSecondRegion();
      if (!mounted) return;
      showSnack(context, '추가한 동네를 지웠어요.');
      Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = widget.services.session.user;
    final current = user?.region ?? '';
    final second = widget.second;
    final hasSecond = user?.secondRegion != null;
    return Scaffold(
      appBar: AppBar(
          title: Text(second ? '동네 추가' : '내 동네 설정',
              style: const TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
      body: SafeArea(
        child: Column(children: [
          Expanded(
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 16), children: [
              Text(second ? '자주 가는 동네를\n하나 더 추가해요' : '어디에 사세요?', style: sbTitle),
              const SizedBox(height: 6),
              Text(
                  second
                      ? '내 동네는 최대 2개예요. 지금 동네: ${current.isEmpty ? '없음' : current}\n'
                          '추가한 동네는 홈 왼쪽 위 동네 이름을 눌러 바꿀 수 있어요.'
                      : '지금 동네: ${current.isEmpty ? '없음' : current}\n홈 위쪽 동네 이름과 동네지도 중심이 이 동네로 바뀌어요.',
                  style: sbBody),
              const SizedBox(height: 20),
              // GPS 로 찾으면 아래 칸에 채워진다 → '이 동네로 설정하기'로 저장(바로 저장하지 않고 확인하게 한다)
              GpsDongButton(
                services: widget.services,
                found: _gpsRegion != null && _input.text.trim() == _gpsRegion ? _gpsRegion : null,
                onFound: (d) => setState(() {
                  _gpsRegion = d.region;
                  _input.text = d.region;
                }),
              ),
              const SizedBox(height: 20),
              Text('또는 직접 입력', style: TextStyle(fontSize: 13, color: Sb.text2)),
              const SizedBox(height: 6),
              TextField(
                controller: _input,
                maxLength: 20,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _save(),
                decoration: sbInput(null, hint: '예) 마포구 망원동', errorText: _error),
              ),
              const SizedBox(height: 8),
              Text('동네지도에 있는 동네', style: TextStyle(fontSize: 13, color: Sb.text2)),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                for (final dong in dongCenters.keys)
                  ChoiceChip(
                    label: Text(_withGu(dong)),
                    selected: _input.text.trim() == _withGu(dong),
                    showCheckmark: false,
                    onSelected: (_) => setState(() {
                      _input.text = _withGu(dong);
                      _error = null;
                    }),
                  ),
              ]),
            ]),
          ),
          if (second && hasSecond)
            TextButton(
              onPressed: _saving ? null : _delete,
              style: TextButton.styleFrom(foregroundColor: Sb.red),
              child: const Text('추가한 동네 지우기'),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
            child: FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                  : Text(second ? (hasSecond ? '이 동네로 바꾸기' : '이 동네 추가하기') : '이 동네로 설정하기'),
            ),
          ),
        ]),
      ),
    );
  }
}
