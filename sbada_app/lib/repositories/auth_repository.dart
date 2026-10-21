import '../models/user.dart';
import '../network/api_client.dart';
import '../network/session.dart';

class AuthRepository {
  AuthRepository(this.api, this.session);

  final ApiClient api;
  final Session session;

  Future<AppUser> login(String username, String password) async {
    final json = await api.post('/api/auth/login',
        auth: false, body: {'username': username.trim(), 'password': password}) as Map<String, dynamic>;
    final user = AppUser.fromJson(json['user'] as Map<String, dynamic>);
    await session.signIn(json['accessToken'] as String, user);
    return user;
  }

  /// 카카오 로그인: 카카오 SDK 가 준 카카오 액세스 토큰을 서버에 보내 스바다 JWT 로 바꾼다(POST /api/auth/kakao).
  /// 처음이면 서버가 계정을 만들어 주고(onboarded=false), 응답 모양은 아이디 로그인과 같다.
  Future<AppUser> kakaoLogin(String kakaoAccessToken) async {
    final json = await api.post('/api/auth/kakao', auth: false, body: {'accessToken': kakaoAccessToken})
        as Map<String, dynamic>;
    final user = AppUser.fromJson(json['user'] as Map<String, dynamic>);
    await session.signIn(json['accessToken'] as String, user);
    return user;
  }

  Future<AppUser> signup({
    required String username,
    required String password,
    required String nickname,
    String? region,
  }) async {
    await api.post('/api/auth/signup', auth: false, body: {
      'username': username.trim(),
      'password': password,
      'nickname': nickname.trim(),
      'region': region == null || region.trim().isEmpty ? null : region.trim(),
    });
    return login(username, password);
  }

  Future<MeProfile> me() async {
    final profile = MeProfile.fromJson(await api.get('/api/me') as Map<String, dynamic>);
    await session.updateUser(profile.user);
    return profile;
  }

  /// 온보딩 결과 저장(PUT /api/me/preferences)
  Future<MeProfile> savePreferences(OnboardingChoice choice) async {
    final profile = MeProfile.fromJson(
        await api.put('/api/me/preferences', body: choice.toJson()) as Map<String, dynamic>);
    await session.updateUser(profile.user);
    return profile;
  }

  /// 내 동네 설정(PUT /api/me/region) — 홈 위쪽 동네 이름 · 동네지도 중심이 바뀐다
  Future<MeProfile> changeRegion(String region) async {
    final profile = MeProfile.fromJson(
        await api.put('/api/me/region', body: {'region': region}) as Map<String, dynamic>);
    await session.updateUser(profile.user);
    return profile;
  }

  /// 내 정보 변경: 닉네임 · 아이디(PUT /api/me/profile). 로그인 토큰은 그대로 쓴다.
  Future<MeProfile> changeProfile({required String username, required String nickname}) async {
    final profile = MeProfile.fromJson(await api.put('/api/me/profile', body: {'username': username, 'nickname': nickname})
        as Map<String, dynamic>);
    await session.updateUser(profile.user);
    return profile;
  }

  /// 비밀번호 변경(PUT /api/me/password, 204). 기존 비밀번호가 틀리면 400 WRONG_PASSWORD
  Future<void> changePassword({required String current, required String next}) async {
    await api.put('/api/me/password', body: {'currentPassword': current, 'newPassword': next});
  }

  /// 프로필 사진(PUT /api/me/photo). 사진은 먼저 POST /api/images 로 올리고 그 번호를 보낸다. null = 지우기
  Future<MeProfile> setPhoto(int? imageId) async {
    final profile = MeProfile.fromJson(
        await api.put('/api/me/photo', body: {'imageId': imageId}) as Map<String, dynamic>);
    await session.updateUser(profile.user);
    return profile;
  }

  Future<Map<String, dynamic>> health() async =>
      await api.get('/api/health', auth: false) as Map<String, dynamic>;
}
