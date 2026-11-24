import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/util/id_input.dart';

// 2026-11-25: 아이디 칸 한영 자동 변환(시뮬레이터에서 한/영 전환이 안 될 때도 영문 아이디가 되게)
void main() {
  test('한글 자모 · 완성형을 두벌식 자판 위치의 영문으로 바꾼다', () {
    expect(toIdText('ㅇㅇㅇㅇ'), 'dddd');
    expect(toIdText('ㅁ으ㅑㅜ'), 'admin'); // 한글 상태로 a-d-m-i-n 을 치면 이렇게 들어온다
    expect(toIdText('ㅡㅑㅜㅓㅑ'), 'minji');
    expect(toIdText('ㅁㅇㅡㅑㅜ'), 'admin');
    expect(toIdText('곽'), 'rhkr'); // 겹모음 ㅘ = hk
    expect(toIdText('닭'), 'ekfr'); // 겹받침 ㄺ = fr
  });

  test('아이디에 못 쓰는 글자는 빼고 대문자는 소문자로', () {
    expect(toIdText('Admin 01!'), 'admin01');
    expect(toIdText('sbx_test'), 'sbx_test');
  });

  test('입력 변환기: 바뀐 글자 수에 맞춰 커서를 옮기고 조합 중 표시는 지운다', () {
    const f = IdInputFormatter();
    final out = f.formatEditUpdate(
      TextEditingValue.empty,
      const TextEditingValue(text: 'ad먀', selection: TextSelection.collapsed(offset: 3), composing: TextRange(start: 2, end: 3)),
    );
    expect(out.text, 'adai');
    expect(out.selection.extentOffset, 4);
    expect(out.composing, TextRange.empty);
    // 영문이면 그대로 둔다
    const same = TextEditingValue(text: 'junho', selection: TextSelection.collapsed(offset: 5));
    expect(f.formatEditUpdate(TextEditingValue.empty, same), same);
  });

  // 2026-11-25 이메일 로그인: @ . 은 그대로 두고 한글은 자판 위치대로
  test('이메일 칸: 한글로 쳐도 영문 이메일이 되고, @ . _ + - 는 남는다', () {
    expect(toEmailText('ㅁ으ㅑㅜ@ㄴㅠㅁㅇㅁ.ㅅㄷㄴㅅ'), 'admin@sbada.test');
    expect(toEmailText('Tester1 @SBADA.test'), 'tester1@sbada.test');
    expect(toEmailText('a.b+c_d-e@x.io'), 'a.b+c_d-e@x.io');
    expect(isEmail('tester1@sbada.test'), isTrue);
    expect(isEmail('tester1'), isFalse);
    expect(isEmail('a@b'), isFalse);
    const f = IdInputFormatter.email();
    final out = f.formatEditUpdate(TextEditingValue.empty,
        const TextEditingValue(text: 'ㅁ@', selection: TextSelection.collapsed(offset: 2)));
    expect(out.text, 'a@');
    expect(out.selection.extentOffset, 2);
  });
}
