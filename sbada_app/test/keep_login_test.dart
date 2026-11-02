import 'package:flutter_test/flutter_test.dart';
import 'package:sbada/models/user.dart';
import 'package:sbada/network/session.dart';

/// 로그인 화면 '로그인 유지'
void main() {
  const user = AppUser(id: 1, username: 'minji', nickname: '민지', region: '망원동', onboarded: true);

  test('로그인 유지 켬: 기기에 저장 → 앱을 다시 켜도(load) 로그인 상태', () async {
    final store = MemoryStore();
    await Session(store).signIn('t1', user);
    final again = Session(store);
    await again.load();
    expect(again.isLoggedIn, isTrue);
  });

  test('로그인 유지 끔: 메모리에만 → 앱을 다시 켜면 로그아웃, 예전에 저장된 로그인도 지운다', () async {
    final store = MemoryStore();
    await Session(store).signIn('old', user); // 예전 로그인
    final s = Session(store);
    await s.signIn('t2', user, persist: false);
    expect(s.isLoggedIn, isTrue);
    await s.updateUser(user); // 이름 바꾸기 등도 저장하지 않는다
    final again = Session(store);
    await again.load();
    expect(again.isLoggedIn, isFalse);
  });

  test('체크 상태는 기억한다(처음엔 켜짐)', () async {
    final s = Session(MemoryStore());
    expect(await s.keepLogin(), isTrue);
    await s.setKeepLogin(false);
    expect(await s.keepLogin(), isFalse);
  });
}
