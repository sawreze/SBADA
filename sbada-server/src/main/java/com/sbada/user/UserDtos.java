package com.sbada.user;

import java.util.List;

import com.sbada.common.Category;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 사용자 관련 API 의 요청/응답 JSON 모양.
 *
 * [프로그래밍 설명]
 *  - 응답 DTO 마다 static from(엔티티) 메서드를 두어 '엔티티 → DTO' 변환 코드를 한 곳에 모았다.
 *  - 같은 사용자라도 보는 사람에 따라 내보내는 정보가 다르다:
 *      UserView   : 나 자신에게 (아이디·온보딩 여부 포함)
 *      PublicUser : 다른 사람에게 (번호·닉네임·동네만) → 물건 상세의 '올린 사람' 등에 쓴다
 */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * 응답에 내보내는 사용자 정보. password_hash 는 절대 넣지 않는다.
     * JSON 예: {"id":1,"username":"minji","nickname":"민지","region":"마포구 망원동","onboarded":true}
     */
    public record UserView(long id, String username, String nickname, String region, boolean onboarded,
                           String profileImageUrl) {
        public static UserView from(AppUser u) {
            String photo = u.getProfileImageId() == null ? null : "/api/images/" + u.getProfileImageId();
            return new UserView(u.getId(), u.getUsername(), u.getNickname(), u.getRegion(), u.isOnboarded(), photo);
        }
    }

    /**
     * 다른 사용자에게 보여 줄 최소 정보
     * JSON 예: {"id":2,"nickname":"준호","region":"마포구 합정동"}
     */
    public record PublicUser(long id, String nickname, String region) {
        public static PublicUser from(AppUser u) {
            return new PublicUser(u.getId(), u.getNickname(), u.getRegion());
        }
    }

    /**
     * 카테고리 선호도 한 줄. weight 는 소수 셋째 자리까지 반올림해 보낸다(0.12345 → 0.123).
     * JSON 예: {"category":"DIGITAL","label":"디지털기기","emoji":"📱","weight":1.0,"source":"ONBOARDING"}
     */
    public record PreferenceView(String category, String label, String emoji, double weight, String source) {
        public static PreferenceView from(UserPreference p) {
            Category c = p.getCategory();
            return new PreferenceView(c.name(), c.label(), c.emoji(),
                    Math.round(p.getWeight() * 1000) / 1000.0, p.getSource().name());
        }
    }

    /** GET /api/me · PUT /api/me/preferences 응답 */
    public record MeView(UserView user, List<PreferenceView> preferences, List<String> keywords,
                         Integer priceMin, Integer priceMax) {
    }

    /**
     * 온보딩 · 선호도 수정 요청(PUT /api/me/preferences 본문)
     *  @NotNull + @Size(min=1): 목록이 없거나 비어 있으면 400
     *  @Min/@Max: 숫자 범위(Integer 가 null 이면 검사하지 않음 → 선택 항목)
     */
    public record PreferencesRequest(
            @NotNull(message = "바꾸고 싶은 물건 종류를 1개 이상 골라 주세요.")
            @Size(min = 1, max = 14, message = "카테고리는 1~14개까지 고를 수 있습니다.")
            List<String> wantedCategories,
            @Size(max = 10, message = "키워드는 10개까지 입력할 수 있습니다.")
            List<String> keywords,
            @Min(value = 0, message = "가격은 0원 이상입니다.") Integer priceMin,
            @Max(value = 100_000_000, message = "가격이 너무 큽니다.") Integer priceMax) {
    }

    /** 내 정보 변경(PUT /api/me/profile 본문) — 가입 때와 같은 규칙 */
    public record ProfileRequest(
            @NotBlank(message = "아이디를 입력해 주세요.")
            @jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9_]{4,20}$", message = "아이디는 영문 소문자·숫자·밑줄 4~20자입니다.")
            String username,
            @NotBlank(message = "닉네임을 입력해 주세요.")
            @Size(min = 2, max = 12, message = "닉네임은 2~12자입니다.")
            String nickname) {
    }

    /** 비밀번호 변경(PUT /api/me/password 본문): 기존 비밀번호가 맞아야 바뀐다 */
    public record PasswordRequest(
            @NotBlank(message = "기존 비밀번호를 입력해 주세요.") String currentPassword,
            @NotBlank(message = "새 비밀번호를 입력해 주세요.")
            @Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다.")
            String newPassword) {
    }

    /** 프로필 사진(PUT /api/me/photo 본문): {"imageId": 12} — null 이면 사진 지우기 */
    public record PhotoRequest(Long imageId) {
    }

    /** 내 동네 설정(PUT /api/me/region 본문): {"region":"마포구 망원동"} — 가입 때와 같은 규칙(20자 이하) */
    public record RegionRequest(
            @NotBlank(message = "동네를 입력해 주세요.")
            @Size(max = 20, message = "동네 이름은 20자 이하입니다.")
            String region) {
    }
}
