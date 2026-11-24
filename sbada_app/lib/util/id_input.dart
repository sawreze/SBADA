import 'package:flutter/services.dart';

// 2026-11-25 사용자 요청: 시뮬레이터에서 아이디를 칠 때 한/영 전환이 안 돼 'ㅇㅇㅇㅇ' 처럼 한글로 들어감.
// 아이디는 영문 소문자 · 숫자 · 밑줄만 쓰므로(서버 규칙 ^[a-z0-9_]{4,20}$),
//  1) 아이디 칸은 영문 키보드(TextInputType.visiblePassword → iOS ASCIICapable)로 열고
//  2) 그래도 한글이 들어오면 두벌식 자판 위치 그대로 영문으로 바꾼다(한영 자동 변환: 'ㅁ으ㅑㅜ' → 'admin').

/// 아이디 칸에 공통으로 쓰는 키보드 설정
const idKeyboardType = TextInputType.visiblePassword;

/// 두벌식 자판: 한글 자모 → 같은 자리의 영문 키
const Map<String, String> _jamoKeys = {
  'ㄱ': 'r', 'ㄲ': 'R', 'ㄳ': 'rt', 'ㄴ': 's', 'ㄵ': 'sw', 'ㄶ': 'sg', 'ㄷ': 'e', 'ㄸ': 'E', 'ㄹ': 'f',
  'ㄺ': 'fr', 'ㄻ': 'fa', 'ㄼ': 'fq', 'ㄽ': 'ft', 'ㄾ': 'fx', 'ㄿ': 'fv', 'ㅀ': 'fg', 'ㅁ': 'a', 'ㅂ': 'q',
  'ㅃ': 'Q', 'ㅄ': 'qt', 'ㅅ': 't', 'ㅆ': 'T', 'ㅇ': 'd', 'ㅈ': 'w', 'ㅉ': 'W', 'ㅊ': 'c', 'ㅋ': 'z',
  'ㅌ': 'x', 'ㅍ': 'v', 'ㅎ': 'g',
  'ㅏ': 'k', 'ㅐ': 'o', 'ㅑ': 'i', 'ㅒ': 'O', 'ㅓ': 'j', 'ㅔ': 'p', 'ㅕ': 'u', 'ㅖ': 'P', 'ㅗ': 'h',
  'ㅘ': 'hk', 'ㅙ': 'ho', 'ㅚ': 'hl', 'ㅛ': 'y', 'ㅜ': 'n', 'ㅝ': 'nj', 'ㅞ': 'np', 'ㅟ': 'nl', 'ㅠ': 'b',
  'ㅡ': 'm', 'ㅢ': 'ml', 'ㅣ': 'l',
};

const _cho = ['ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];
const _jung = ['ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'];
const _jong = ['', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];

/// 한글(완성형 · 자모)을 두벌식 자판 위치의 영문으로 바꾼 뒤, 아이디에 쓸 수 있는 글자(a-z · 0-9 · _)만 남긴다(대문자는 소문자로)
String toIdText(String input) {
  final b = StringBuffer();
  for (final r in input.runes) {
    final ch = String.fromCharCode(r);
    if (r >= 0xAC00 && r <= 0xD7A3) {
      final s = r - 0xAC00;
      b
        ..write(_jamoKeys[_cho[s ~/ 588]])
        ..write(_jamoKeys[_jung[(s % 588) ~/ 28]])
        ..write(_jong[s % 28].isEmpty ? '' : _jamoKeys[_jong[s % 28]]);
    } else {
      b.write(_jamoKeys[ch] ?? ch);
    }
  }
  return b.toString().toLowerCase().replaceAll(RegExp(r'[^a-z0-9_]'), '');
}

/// 아이디 칸 입력 변환기: 한글로 쳐도 영문 아이디가 되게 한다
class IdInputFormatter extends TextInputFormatter {
  const IdInputFormatter();

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final text = toIdText(newValue.text);
    if (text == newValue.text) return newValue;
    // 커서 앞부분만 따로 바꿔서 커서 위치를 맞춘다
    final end = newValue.selection.isValid ? newValue.selection.extentOffset.clamp(0, newValue.text.length) : newValue.text.length;
    final offset = toIdText(newValue.text.substring(0, end)).length;
    return TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: offset.clamp(0, text.length)),
      composing: TextRange.empty,
    );
  }
}
