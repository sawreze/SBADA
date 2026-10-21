package com.sbada.item;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * ┌──────────────────────────────────────────────────────────────┐
 * │ 물건(상품) API — CRUD                                           │
 * └──────────────────────────────────────────────────────────────┘
 *  GET    /api/items          공개  목록(최신순, 카테고리·검색어로 거르기)
 *  GET    /api/items/{id}     공개  상세(로그인했으면 조회 수·선호도 학습)
 *  GET    /api/me/items       🔒    내 물건 목록(교환 완료 포함, 내린 물건 제외)
 *  POST   /api/items          🔒    등록 → 201 Created
 *  PUT    /api/items/{id}     🔒    수정(이름·설명·원하는 카테고리만) — 주인만
 *  DELETE /api/items/{id}     🔒    내리기 → 204 No Content — 주인만
 *
 * [프로그래밍 설명 — REST 설계]
 *  - '자원(물건)'을 주소로, '행동'을 HTTP 방식으로 표현한다:
 *      GET(조회) · POST(생성) · PUT(전체 수정) · DELETE(삭제)  ← 같은 /api/items/{id} 라도 방식에 따라 다른 메서드가 실행된다.
 *  - 이 클래스는 @RequestMapping 을 클래스에 두지 않고 메서드마다 전체 경로를 적었다(/api/me/items 가 섞여 있어서).
 *  - 응답 상태 코드 관례: 조회·수정 200, 생성 201(+ Location 헤더), 삭제 204(본문 없음).
 *  - 권한 검사 순서(7장): 401 로그인 → 404 존재 → 403 주인 → 409 상태 충돌 (ItemService.requireOwned)
 */
@RestController
public class ItemController {

    private final ItemService service;

    public ItemController(ItemService service) {
        this.service = service;
    }

    /**
     * ■ 물건 목록   GET /api/items?category=DIGITAL&q=에어팟&limit=50   (공개)
     *   공개 목록(최신순). category·q 로 거를 수 있다. 목록 모양은 {"items": [...]}
     *
     * 쿼리 파라미터(@RequestParam = 주소 ? 뒤의 값)
     *   category  선택  Category 이름(DIGITAL, SPORTS …). 없으면 전체        (required = false → 없으면 null)
     *   q         선택  이름에 들어간 검색어(대소문자 무시, 부분 일치)
     *   limit     선택  기본 50, 1~100 으로 잘라 씀(Math.max/min)          (defaultValue = "50")
     *   → 교환 가능(AVAILABLE)한 물건만, 최근 등록 순(id 내림차순)
     *
     * 응답 200 — ItemDtos.ItemList
     *   {"items": [ { ItemView… }, { ItemView… } ]}     (ItemView 모양은 아래 상세 참고)
     *   목록을 배열 그대로가 아니라 {"items": […]} 객체로 감싸는 이유: 나중에 total, nextPage 같은 필드를
     *   추가해도 앱이 깨지지 않게 하려고(10장 3.1).
     * 오류: 400 INVALID_CATEGORY
     */
    @GetMapping("/api/items")
    public ItemDtos.ItemList list(@RequestParam(required = false) String category,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "50") int limit) {
        return service.list(category, q, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * ■ 물건 상세   GET /api/items/{id}   (공개, 토큰이 있으면 '누가 봤는지' 기록)
     *   공개 상세. 로그인한 사람이 보면 조회 기록이 선호도 학습에 쓰인다.
     *
     * 응답 200 — ItemDtos.ItemView
     *   {
     *     "id": 5, "title": "레고 테크닉 세트", "description": "박스 구성품 다 있어요",
     *     "category": "HOBBY", "categoryLabel": "취미/게임/음반", "categoryEmoji": "🎮",
     *     "condition": "GOOD", "conditionLabel": "사용감 적음",
     *     "status": "AVAILABLE",                           // AVAILABLE / TRADED
     *     "price": 140000, "priceMin": 120000, "priceMax": 160000,   // 서버(AI)가 정한 가격
     *     "aiConfidence": 0.7, "aiReason": "…", "aiProvider": "google",   // seed = 시연용 표본
     *     "wishCategories": ["DIGITAL"], "wishCategoryLabels": ["디지털기기"],
     *     "imageUrls": ["/api/images/5"],
     *     "owner": {"id": 4, "nickname": "도현", "region": "마포구 서교동"},
     *     "viewCount": 12, "requestCount": 1, "createdAt": "2026-10-21T02:00:00Z"
     *   }
     * 오류: 404 ITEM_NOT_FOUND(없거나 내린 물건), 400 INVALID_PARAMETER(id 가 숫자가 아님)
     *
     * 공개 주소라서 토큰이 없으면 auth 가 null 로 들어온다.
     * auth.getPrincipal() instanceof Long uid → '패턴 매칭 instanceof'(Java 16+): Long 이면 uid 변수에 바로 담는다.
     */
    @GetMapping("/api/items/{id}")
    public ItemDtos.ItemView get(@PathVariable long id, Authentication auth) {
        Long viewer = auth != null && auth.getPrincipal() instanceof Long uid ? uid : null;
        return service.get(id, viewer);
    }

    /**
     * ■ 내 물건   GET /api/me/items   (🔒)
     * 응답 200 — {"items": [ItemView…]}  내 물건 중 내리지(HIDDEN) 않은 것, 최신순(교환 완료 TRADED 포함)
     * (교환 요청 보낼 때 '내놓을 물건 고르기', 계정정보 > 내 물건 화면에서 쓴다)
     */
    @GetMapping("/api/me/items")
    public ItemDtos.ItemList mine(Authentication auth) {
        return service.mine((Long) auth.getPrincipal());
    }

    /**
     * ■ 물건 등록   POST /api/items   (🔒)
     *
     * 요청 본문 — ItemDtos.CreateItemRequest  (price 는 받지 않는다! 보내면 400 UNKNOWN_FIELD)
     *   {
     *     "title": "에어팟 프로 2세대",        // 필수, 60자 이하
     *     "description": "케이스 포함",        // 선택, 1000자 이하
     *     "category": "DIGITAL",               // 필수
     *     "condition": "GOOD",                 // 필수
     *     "imageIds": [33],                    // 필수, 1~5장, 내가 올린 사진 번호
     *     "analysisId": 129,                   // 선택, POST /api/ai/analyze 의 결과 번호
     *     "wishCategories": ["SPORTS"]         // 선택, 바꾸고 싶은 물건 종류 5개까지
     *   }
     *
     * 처리 흐름 (ItemService.create)
     *   ① 분석 기록이 내 것이고, 그 사진이 등록할 사진에 들어 있는지 확인
     *   ② 분석이 없거나 사용자가 카테고리·상태를 바꿨으면 서버가 다시 분석(가격은 항상 서버가 정함)
     *   ③ 트랜잭션 안에서: 사진들이 내 것·미사용인지 확인 → items INSERT → 사진에 item_id 연결
     *   ④ 커밋 후: 비슷한 가격대 물건을 가진 사람 상위 5명에게 '바꿀 만한 물건' 알림(RecommendationService)
     *
     * 응답 201 — ItemView (Location: /api/items/{새 번호})
     * 오류
     *   400 VALIDATION_FAILED / INVALID_CATEGORY / INVALID_CONDITION / UNKNOWN_FIELD(price 등) / ANALYSIS_IMAGE_MISMATCH
     *   401 AUTH_REQUIRED
     *   403 NOT_IMAGE_OWNER / NOT_ANALYSIS_OWNER
     *   404 IMAGE_NOT_FOUND / ANALYSIS_NOT_FOUND
     *   409 IMAGE_ALREADY_USED(다른 물건에 이미 쓴 사진)
     */
    @PostMapping("/api/items")
    public ResponseEntity<ItemDtos.ItemView> create(@Valid @RequestBody ItemDtos.CreateItemRequest input,
                                                    Authentication auth) {
        ItemDtos.ItemView item = service.create((Long) auth.getPrincipal(), input);
        return ResponseEntity.created(URI.create("/api/items/" + item.id())).body(item);
    }

    /**
     * ■ 물건 수정   PUT /api/items/{id}   (🔒, 주인만)
     *
     * 요청 본문 — ItemDtos.UpdateItemRequest  (가격·카테고리·상태·사진은 바꿀 수 없다)
     *   {"title": "에어팟 프로 2세대 (급처)", "description": "…", "wishCategories": ["SPORTS","HOBBY"]}
     *
     * 응답 200 — 바뀐 ItemView
     * 오류: 400 VALIDATION_FAILED, 401, 403 NOT_OWNER(남의 물건), 404 ITEM_NOT_FOUND
     * (현재 앱 화면에는 수정 기능이 없고 API 만 있다 — 기능 명세서 '제외 범위')
     */
    @PutMapping("/api/items/{id}")
    public ItemDtos.ItemView update(@PathVariable long id, @Valid @RequestBody ItemDtos.UpdateItemRequest input,
                                    Authentication auth) {
        return service.update(id, (Long) auth.getPrincipal(), input);
    }

    /**
     * ■ 물건 내리기   DELETE /api/items/{id}   (🔒, 주인만)
     *   DB 행을 지우지 않고 상태를 HIDDEN 으로 바꾼다(교환·채팅 기록이 이 물건을 가리키고 있어서).
     *   이 물건에 걸려 있던 대기 중 교환 요청은 모두 자동 취소되고 상대에게 알림이 간다.
     *
     * 응답 204 No Content (본문 없음). ResponseEntity<Void> + noContent().build()
     * 오류: 401, 403 NOT_OWNER, 404 ITEM_NOT_FOUND(이미 내린 물건 포함),
     *       409 ITEM_TRADED(교환이 끝난 물건은 기록 보존을 위해 내릴 수 없음 — Item.hide() 가 던짐)
     */
    @DeleteMapping("/api/items/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, Authentication auth) {
        service.delete(id, (Long) auth.getPrincipal());
        return ResponseEntity.noContent().build();
    }
}
