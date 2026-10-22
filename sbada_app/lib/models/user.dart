import 'json.dart';

class AppUser {
  const AppUser({
    required this.id,
    required this.username,
    required this.nickname,
    required this.region,
    required this.onboarded,
    this.profileImageUrl,
    this.secondRegion,
  });

  final int id;
  final String username;
  final String nickname;
  final String region;
  final bool onboarded;

  /// 프로필 사진 주소(/api/images/12). 없으면 null → 닉네임 첫 글자
  final String? profileImageUrl;

  /// 하나 더 추가한 동네(내 동네는 최대 2개). 없으면 null. 홈 왼쪽 위 동네 이름을 눌러 region 과 바꾼다.
  final String? secondRegion;

  factory AppUser.fromJson(Map<String, dynamic> j) => AppUser(
        id: reqInt(j, 'id'),
        username: reqStr(j, 'username'),
        nickname: reqStr(j, 'nickname'),
        region: optStr(j, 'region') ?? '',
        onboarded: optBool(j, 'onboarded'),
        profileImageUrl: optStr(j, 'profileImageUrl'),
        secondRegion: optStr(j, 'secondRegion'),
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'username': username,
        'nickname': nickname,
        'region': region,
        'onboarded': onboarded,
        'profileImageUrl': profileImageUrl,
        'secondRegion': secondRegion,
      };
}

/// 다른 사람에게 보이는 정보
class PublicUser {
  const PublicUser({required this.id, required this.nickname, required this.region});

  final int id;
  final String nickname;
  final String region;

  factory PublicUser.fromJson(Map<String, dynamic> j) => PublicUser(
        id: reqInt(j, 'id'),
        nickname: reqStr(j, 'nickname'),
        region: optStr(j, 'region') ?? '',
      );
}

class PreferenceWeight {
  const PreferenceWeight({
    required this.category,
    required this.label,
    required this.emoji,
    required this.weight,
    required this.source,
  });

  final String category;
  final String label;
  final String emoji;
  final double weight;

  /// ONBOARDING(직접 고름) · BEHAVIOR(행동으로 학습)
  final String source;

  factory PreferenceWeight.fromJson(Map<String, dynamic> j) => PreferenceWeight(
        category: reqStr(j, 'category'),
        label: reqStr(j, 'label'),
        emoji: optStr(j, 'emoji') ?? '',
        weight: optDouble(j, 'weight'),
        source: optStr(j, 'source') ?? 'BEHAVIOR',
      );
}

class MeProfile {
  const MeProfile({
    required this.user,
    required this.preferences,
    required this.keywords,
    this.priceMin,
    this.priceMax,
  });

  final AppUser user;
  final List<PreferenceWeight> preferences;
  final List<String> keywords;
  final int? priceMin;
  final int? priceMax;

  factory MeProfile.fromJson(Map<String, dynamic> j) => MeProfile(
        user: AppUser.fromJson(j['user'] as Map<String, dynamic>),
        preferences: objList(j, 'preferences', PreferenceWeight.fromJson),
        keywords: strList(j, 'keywords'),
        priceMin: optInt(j, 'priceMin'),
        priceMax: optInt(j, 'priceMax'),
      );
}

/// 온보딩에서 고른 값(로그인 전에는 기기에 잠시 보관했다가 로그인 후 서버로 보낸다)
class OnboardingChoice {
  const OnboardingChoice({
    required this.wantedCategories,
    required this.keywords,
    this.priceMin,
    this.priceMax,
  });

  final List<String> wantedCategories;
  final List<String> keywords;
  final int? priceMin;
  final int? priceMax;

  Map<String, dynamic> toJson() => {
        'wantedCategories': wantedCategories,
        'keywords': keywords,
        'priceMin': priceMin,
        'priceMax': priceMax,
      };

  factory OnboardingChoice.fromJson(Map<String, dynamic> j) => OnboardingChoice(
        wantedCategories: strList(j, 'wantedCategories'),
        keywords: strList(j, 'keywords'),
        priceMin: optInt(j, 'priceMin'),
        priceMax: optInt(j, 'priceMax'),
      );
}
