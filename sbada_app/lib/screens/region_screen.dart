import 'package:flutter/material.dart';

import '../map/geo.dart';
import '../network/api_exception.dart';
import '../services/app_services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// 내 동네 설정(어디에 사는지). PUT /api/me/region
///  - 홈 위쪽 동네 이름 · 동네지도 중심 · 내 물건 위치(동네 중심 근처)가 이 값으로 바뀐다.
///  - 직접 입력하거나, 동네지도에 있는 동네 칩을 눌러 고른다. 20자 이하(가입 때와 같은 규칙).
class RegionScreen extends StatefulWidget {
  const RegionScreen({super.key, required this.services});

  final AppServices services;

  @override
  State<RegionScreen> createState() => _RegionScreenState();
}

class _RegionScreenState extends State<RegionScreen> {
  late final TextEditingController _input =
      TextEditingController(text: widget.services.session.user?.region ?? '');
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

  @override
  Widget build(BuildContext context) {
    final current = widget.services.session.user?.region ?? '';
    return Scaffold(
      appBar: AppBar(title: const Text('내 동네 설정', style: TextStyle(fontSize: 19, fontWeight: FontWeight.w800))),
      body: SafeArea(
        child: Column(children: [
          Expanded(
            child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 16), children: [
              Text('어디에 사세요?', style: sbTitle),
              const SizedBox(height: 6),
              Text('지금 동네: ${current.isEmpty ? '없음' : current}\n홈 위쪽 동네 이름과 동네지도 중심이 이 동네로 바뀌어요.', style: sbBody),
              const SizedBox(height: 20),
              Text('사는 동네', style: TextStyle(fontSize: 13, color: Sb.text2)),
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
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
            child: FilledButton(
              onPressed: _saving ? null : _save,
              child: _saving
                  ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5, color: Sb.onAccent))
                  : const Text('이 동네로 설정하기'),
            ),
          ),
        ]),
      ),
    );
  }
}
