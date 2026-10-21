package com.sbada.user;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.ApiException;
import com.sbada.common.Category;
import com.sbada.image.ImageService;
import com.sbada.image.ItemImage;

import jakarta.validation.Valid;

/**
 * ┌──────────────────────────────────────────────────────────┐
 * │ 내 정보 · 온보딩 취향 API   (기본 주소: /api/me, 🔒 로그인 필요) │
 * └──────────────────────────────────────────────────────────┘
 *  GET /api/me              → 내 정보 + 카테고리 선호도 + 키워드 + 가격대
 *  PUT /api/me/preferences  → 온보딩에서 고른 취향 저장(다시 보내면 덮어씀)
 *  (내 물건 목록 GET /api/me/items 는 ItemController 에 있다)
 *
 * [프로그래밍 설명 — '로그인한 사용자'를 받는 법]
 *  - 메서드 파라미터에 Authentication auth 를 두면, JwtAuthenticationFilter 가 SecurityContext 에 넣어 둔
 *    인증 정보가 자동으로 들어온다. auth.getPrincipal() = 토큰의 sub(사용자 번호, Long).
 *  - 그래서 클라이언트는 '내 번호'를 주소나 본문에 보내지 않는다. 토큰으로만 누구인지 정한다
 *    → 남의 번호를 넣어 남의 정보를 바꾸는 공격을 막는다.
 *  - 이 Controller 는 Service 를 따로 두지 않고 Repository·PreferenceService 를 직접 쓴다(조회가 단순해서).
 */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AppUserRepository users;
    private final PreferenceService preferenceService;
    private final PasswordEncoder passwordEncoder;
    private final ImageService images;

    public MeController(AppUserRepository users, PreferenceService preferenceService, PasswordEncoder passwordEncoder,
                        ImageService images) {
        this.users = users;
        this.preferenceService = preferenceService;
        this.passwordEncoder = passwordEncoder;
        this.images = images;
    }

    /**
     * ■ 내 정보   GET /api/me   (🔒)
     *   헤더: Authorization: Bearer {accessToken}
     *
     * 응답 200 — UserDtos.MeView
     *   {
     *     "user": {"id":1,"username":"minji","nickname":"민지","region":"마포구 망원동","onboarded":true},
     *     "preferences": [                                  // 선호도 높은 순
     *       {"category":"DIGITAL","label":"디지털기기","emoji":"📱","weight":1.0,"source":"ONBOARDING"},
     *       {"category":"SPORTS","label":"스포츠/레저","emoji":"⚽","weight":0.23,"source":"BEHAVIOR"}
     *     ],
     *     "keywords": ["닌텐도","캠핑"],
     *     "priceMin": 30000, "priceMax": 300000
     *   }
     * 오류: 401 AUTH_REQUIRED(토큰 없음) / TOKEN_EXPIRED / TOKEN_INVALID(사용자 없음)
     *
     * 앱은 시작할 때(splash) 이 API 로 토큰이 아직 유효한지와 온보딩을 마쳤는지(onboarded)를 확인한다.
     */
    @GetMapping
    public UserDtos.MeView me(Authentication auth) {
        long userId = (Long) auth.getPrincipal(); // Object → Long 형변환 후 언박싱(long)
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
        // 스트림: 엔티티 목록 → map(DTO 로 변환) → toList(). PreferenceView::from 은 메서드 참조(p -> PreferenceView.from(p))
        List<UserDtos.PreferenceView> prefs = preferenceService.listOf(userId).stream()
                .map(UserDtos.PreferenceView::from).toList();
        return new UserDtos.MeView(UserDtos.UserView.from(user), prefs,
                new ArrayList<>(user.getKeywords()), user.getPriceMin(), user.getPriceMax());
    }

    /**
     * ■ 온보딩 취향 저장   PUT /api/me/preferences   (🔒)
     *   첫 실행 온보딩(바꾸고 싶은 물건·관심 키워드·가격대) 저장. 다시 보내면 덮어쓴다.
     *   PUT = '이 자원을 보낸 내용으로 통째로 바꾼다'는 뜻(일부만 바꾸는 PATCH 와 구분).
     *
     * 요청 본문 — UserDtos.PreferencesRequest
     *   {
     *     "wantedCategories": ["DIGITAL","HOBBY"],   // 필수, 1~14개, Category 이름
     *     "keywords": ["닌텐도","캠핑"],              // 선택, 10개까지
     *     "priceMin": 30000, "priceMax": 300000      // 선택(null = 상관없음)
     *   }
     *
     * 처리 흐름
     *   ① 카테고리 문자열 → Category enum (모르는 값이면 400 INVALID_CATEGORY)
     *   ② PreferenceService.saveOnboarding: 고른 카테고리 선호도 = 1.0, 키워드·가격대 저장, onboarded = true
     *   ③ 저장된 결과를 GET /api/me 와 같은 모양으로 돌려준다(같은 클래스의 me() 를 다시 호출)
     *
     * 응답 200 — MeView (위 GET 과 같은 모양)
     * 오류: 400 VALIDATION_FAILED / INVALID_CATEGORY / INVALID_PRICE_RANGE(최소 > 최대), 401
     */
    @PutMapping("/preferences")
    public UserDtos.MeView savePreferences(@Valid @RequestBody UserDtos.PreferencesRequest input,
                                           Authentication auth) {
        long userId = (Long) auth.getPrincipal();
        // EnumSet: enum 전용 Set(내부적으로 비트로 저장해 빠르다). 중복 카테고리는 자동으로 하나만 남는다.
        Set<Category> wanted = EnumSet.noneOf(Category.class);
        for (String raw : input.wantedCategories()) {
            wanted.add(Category.parseStrict(raw));
        }
        Set<String> keywords = input.keywords() == null ? Set.of() : new LinkedHashSet<>(input.keywords());
        preferenceService.saveOnboarding(userId, wanted, keywords, input.priceMin(), input.priceMax());
        return me(auth);
    }

    /**
     * ■ 내 동네 설정   PUT /api/me/region   (🔒)
     *   계정정보 > 내 동네 설정에서 '어디에 사는지'를 바꾼다. 홈 위쪽 동네 이름 · 동네지도 중심 · 물건 위치(동네 중심 근처)에 쓰인다.
     *
     * 요청 본문 — UserDtos.RegionRequest   {"region": "마포구 합정동"}   (필수, 20자 이하)
     * 응답 200 — MeView (GET /api/me 와 같은 모양, user.region 이 바뀜)
     * 오류: 400 VALIDATION_FAILED(빈 값 · 20자 초과), 401
     *
     * [프로그래밍 설명] @Transactional 없이도 save() 로 바로 저장된다(SimpleJpaRepository.save 가 트랜잭션을 연다).
     */
    @PutMapping("/region")
    public UserDtos.MeView changeRegion(@Valid @RequestBody UserDtos.RegionRequest input, Authentication auth) {
        long userId = (Long) auth.getPrincipal();
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
        user.changeRegion(input.region());
        users.save(user);
        return me(auth);
    }

    /**
     * ■ 내 정보 변경   PUT /api/me/profile   (🔒)   계정정보 > 내 정보
     * 요청: {"username":"minji2","nickname":"민지"}  — 가입 때와 같은 규칙(아이디 영문 소문자·숫자·밑줄 4~20자, 닉네임 2~12자)
     * 응답 200 — MeView
     * 오류: 400 VALIDATION_FAILED · 409 USERNAME_EXISTS(다른 사람이 쓰는 아이디) · 401
     * 아이디를 바꿔도 로그인 토큰은 사용자 번호(sub) 기준이라 다시 로그인할 필요가 없다.
     */
    @PutMapping("/profile")
    public UserDtos.MeView changeProfile(@Valid @RequestBody UserDtos.ProfileRequest input, Authentication auth) {
        AppUser user = requireMe(auth);
        String username = input.username().trim();
        if (!username.equals(user.getUsername()) && users.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_EXISTS", "이미 사용 중인 아이디입니다.");
        }
        user.changeProfile(username, input.nickname());
        users.save(user);
        return me(auth);
    }

    /**
     * ■ 비밀번호 변경   PUT /api/me/password   (🔒)   계정정보 > 내 정보
     * 요청: {"currentPassword":"지금 비밀번호","newPassword":"새 비밀번호(8~64자)"}
     * 응답 204(본문 없음)
     * 오류: 400 WRONG_PASSWORD(기존 비밀번호 틀림) · PASSWORD_TOO_LONG(UTF-8 72바이트 초과, BCrypt 한계) · VALIDATION_FAILED · 401
     * 카카오로 가입한 계정은 비밀번호가 없어(쓸 수 없는 난수) 여기서 바꿀 수 없다 → WRONG_PASSWORD.
     */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody UserDtos.PasswordRequest input, Authentication auth) {
        AppUser user = requireMe(auth);
        if (!passwordEncoder.matches(input.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "기존 비밀번호가 맞지 않아요.");
        }
        if (input.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "비밀번호가 너무 깁니다(UTF-8 72바이트 이하).");
        }
        user.changePasswordHash(passwordEncoder.encode(input.newPassword()));
        users.save(user);
        return ResponseEntity.noContent().build();
    }

    /**
     * ■ 프로필 사진   PUT /api/me/photo   (🔒)   계정정보 위쪽 동그란 프로필의 카메라 버튼
     * 순서: 앱이 사진을 POST /api/images 로 먼저 올리고 → 받은 id 를 여기에 보낸다. {"imageId":12}  (null = 사진 지우기)
     * 응답 200 — MeView (user.profileImageUrl = "/api/images/12")
     * 오류: 404 IMAGE_NOT_FOUND · 403 NOT_IMAGE_OWNER(남이 올린 사진) · 401
     */
    @PutMapping("/photo")
    public UserDtos.MeView changePhoto(@RequestBody UserDtos.PhotoRequest input, Authentication auth) {
        AppUser user = requireMe(auth);
        if (input.imageId() != null) {
            ItemImage image = images.require(input.imageId());
            if (image.getUploader().getId() != user.getId().longValue()) {
                throw ApiException.forbidden("NOT_IMAGE_OWNER", "내가 올린 사진만 프로필로 쓸 수 있어요.");
            }
        }
        user.changeProfileImage(input.imageId());
        users.save(user);
        return me(auth);
    }

    private AppUser requireMe(Authentication auth) {
        long userId = (Long) auth.getPrincipal();
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
    }
}
