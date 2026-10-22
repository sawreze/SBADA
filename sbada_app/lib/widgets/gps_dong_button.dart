import 'package:flutter/material.dart';

import '../services/app_services.dart';
import '../services/dong_finder.dart';
import '../services/location/location.dart';
import '../theme/app_theme.dart';
import 'common.dart';

/// '현재 위치로 동네 찾기' 버튼(온보딩 ④ · 계정정보 > 내 동네 설정 · 회원가입 동네 칸 아래).
/// [compact] = 회원가입처럼 아래에 큰 버튼('가입하고 시작하기')이 또 있는 화면용 작은 테두리 버튼.
/// 누르면: 위치 권한(처음 한 번) → GPS → 서버가 동네 이름으로 바꿈 → [onFound]('서울 마포구 망원동').
/// 찾는 동안은 버튼에 '위치 찾는 중…', 실패하면 버튼 아래에 이유와 '직접 입력' 안내를 보여 준다.
class GpsDongButton extends StatefulWidget {
  const GpsDongButton({super.key, required this.services, required this.onFound, this.found, this.compact = false});

  final AppServices services;
  final ValueChanged<FoundDong> onFound;

  /// 이미 찾은 동네(있으면 버튼 아래에 '찾은 동네' 표시)
  final String? found;

  /// 작은 테두리 버튼으로 그린다(회원가입)
  final bool compact;

  @override
  State<GpsDongButton> createState() => _GpsDongButtonState();
}

class _GpsDongButtonState extends State<GpsDongButton> {
  bool _busy = false;
  String? _error;

  Future<void> _find() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final found = await DongFinder.find(widget.services.api, widget.services.session.store);
      if (!mounted) return;
      widget.onFound(found);
    } on LocationException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (e) {
      if (mounted) setState(() => _error = messageOf(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final found = widget.found;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (widget.compact)
        Align(
          alignment: Alignment.centerLeft,
          child: OutlinedButton.icon(
            key: const ValueKey('gps-find-dong'),
            style: OutlinedButton.styleFrom(
              foregroundColor: Sb.mintText,
              side: BorderSide(color: Sb.mintText),
              minimumSize: const Size(0, 40),
              padding: const EdgeInsets.symmetric(horizontal: 14),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(999)),
            ),
            onPressed: _busy ? null : _find,
            icon: _busy
                ? SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2.2, color: Sb.mintText))
                : const Icon(Icons.my_location_rounded, size: 18),
            label: Text(_busy ? '위치 찾는 중…' : '현재 위치로 찾기',
                style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
          ),
        )
      else
      SizedBox(
        height: 54,
        child: FilledButton.icon(
          key: const ValueKey('gps-find-dong'),
          style: FilledButton.styleFrom(
            backgroundColor: Sb.blue,
            foregroundColor: Sb.onAccent,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          ),
          onPressed: _busy ? null : _find,
          icon: _busy
              ? const SizedBox(
                  width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2.4, color: Sb.onAccent))
              : const Icon(Icons.my_location_rounded),
          label: Text(_busy ? '위치 찾는 중…' : '현재 위치로 동네 찾기',
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800)),
        ),
      ),
      if (_error != null)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: Text(_error!, style: TextStyle(fontSize: 13, color: Sb.red, height: 1.4)),
        )
      else if (found != null && found.isNotEmpty)
        Padding(
          padding: const EdgeInsets.only(top: 10),
          child: Row(children: [
            Icon(Icons.place_rounded, size: 18, color: Sb.mintText),
            const SizedBox(width: 6),
            Expanded(
              child: Text.rich(
                TextSpan(children: [
                  TextSpan(
                      text: found.split(' ').last,
                      style: TextStyle(fontWeight: FontWeight.w800, color: Sb.navy, fontSize: 15)),
                  TextSpan(text: '  $found', style: TextStyle(color: Sb.text2, fontSize: 13)),
                ]),
              ),
            ),
            Icon(Icons.check_circle, color: Sb.mintText, size: 20),
          ]),
        ),
    ]);
  }
}
