package com.sbada.exchange;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * ┌───────────────────────────────────────────────────────────────────┐
 * │ 교환('바로 바꾸기') API   (기본 주소: /api/exchanges, 🔒 모두 로그인 필요) │
 * └───────────────────────────────────────────────────────────────────┘
 *  POST /api/exchanges               교환 요청 보내기 → 201  (상대에게 알림·푸시)
 *  GET  /api/exchanges?box=received  받은 요청 목록 (box=sent 면 보낸 요청)
 *  GET  /api/exchanges/{id}          요청 하나 보기(당사자만)
 *  POST /api/exchanges/{id}/accept   수락 — 받은 사람만 → 두 물건 TRADED
 *  POST /api/exchanges/{id}/reject   거절 — 받은 사람만
 *  POST /api/exchanges/{id}/cancel   취소 — 보낸 사람만
 *
 * [프로그래밍 설명 — 상태를 바꾸는 '행동' API]
 *  - accept/reject/cancel 은 '자원을 만드는 것'이 아니라 상태 전이(REQUESTED → ACCEPTED 등)라서
 *    /{id}/accept 처럼 동사를 붙인 POST 로 만들었다(REST 에서 흔히 쓰는 '액션 엔드포인트').
 *  - 상태 규칙(ExchangeStatus): REQUESTED 에서만 바뀔 수 있고, 이미 끝난 요청을 다시 처리하면 409 INVALID_STATUS.
 *  - 검사 순서: 401(로그인) → 404(요청 있음?) → 403(내가 처리할 권한?) → 409(지금 상태에서 가능?) → 값 변경
 *  - 응답 ExchangeView 예시(모든 메서드 공통 모양)
 *    {
 *      "id": 7, "status": "REQUESTED", "message": "토요일에 바꿔요",
 *      "requester":   {"id":2,"nickname":"준호","region":"마포구 합정동"},      // 보낸 사람
 *      "targetOwner": {"id":1,"nickname":"민지","region":"마포구 망원동"},      // 받은 사람
 *      "offeredItem": {"id":9,"title":"로드 자전거 헬멧","price":160000,"imageUrl":"/api/images/9",
 *                      "status":"AVAILABLE","category":"SPORTS","categoryLabel":"스포츠/레저","ownerId":2},
 *      "targetItem":  {"id":3,"title":"에어팟 프로","price":150000, … ,"ownerId":1},
 *      "priceDiffPercent": 7,           // (내놓은 가격 − 원하는 가격) ÷ 원하는 가격 × 100
 *      "chatRoomId": 4,                 // 요청과 함께 열린 채팅방
 *      "closeReason": null,             // 거절·취소 이유
 *      "createdAt": "2026-10-21T04:00:00Z", "respondedAt": null,
 *      "myRole": "RECEIVER"             // 이 응답을 받는 사람 입장: REQUESTER / RECEIVER
 *    }
 */
@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {

    private final ExchangeService service;

    public ExchangeController(ExchangeService service) {
        this.service = service;
    }

    /**
     * ■ 교환 요청(바로 바꾸기)   POST /api/exchanges
     *   '바로 바꾸기' — 상대에게 알림이 가고, 상대가 수락해야 교환이 완료된다.
     *
     * 요청 본문 — ExchangeDtos.CreateRequest
     *   {"targetItemId": 3, "offeredItemId": 9, "message": "토요일에 바꿔요"}
     *     targetItemId  필수  내가 갖고 싶은 '남의' 물건
     *     offeredItemId 필수  내가 내놓는 '내' 물건
     *     message       선택  200자 이하
     *
     * 처리 흐름 (ExchangeService.create, 한 트랜잭션)
     *   ① 두 물건 확인(없거나 내린 물건 404) → 내 물건인지(403) → 내 물건끼리가 아닌지(400) → 둘 다 교환 가능한지(409)
     *   ② 같은 조합 대기 요청이 이미 있으면 409(중복), 상대가 반대로 보낸 요청이 있으면 409(그걸 수락하라고 안내)
     *   ③ exchange_requests INSERT, 상대 물건의 요청 수 +1
     *   ④ 두 사람의 채팅방을 열고(없으면 생성) '제안 카드' 메시지를 남김
     *   ⑤ 선호도 학습(+0.2) → 상대에게 알림(DB 저장 + WebSocket 실시간 / 앱이 꺼져 있으면 FCM)
     *   ⑥ 커밋 후 두 사람 화면에 'exchange.updated' 실시간 이벤트
     *
     * 응답 201 — ExchangeView (Location: /api/exchanges/{id})
     * 오류
     *   400 VALIDATION_FAILED / SELF_EXCHANGE(내 물건끼리)
     *   403 NOT_OWNER(남의 물건을 내놓음)
     *   404 ITEM_NOT_FOUND
     *   409 ITEM_NOT_AVAILABLE(이미 교환됨) / DUPLICATE_REQUEST / REVERSE_REQUEST_EXISTS
     */
    @PostMapping
    public ResponseEntity<ExchangeDtos.ExchangeView> create(@Valid @RequestBody ExchangeDtos.CreateRequest input,
                                                            Authentication auth) {
        ExchangeDtos.ExchangeView view = service.create((Long) auth.getPrincipal(), input.targetItemId(),
                input.offeredItemId(), input.message());
        return ResponseEntity.created(URI.create("/api/exchanges/" + view.id())).body(view);
    }

    /**
     * ■ 교환 요청 목록   GET /api/exchanges?box=received   또는   ?box=sent
     *   box=received(받은 요청, 기본) | sent(보낸 요청)
     *   @RequestParam(defaultValue = "received"): 주소에 box 가 없으면 "received" 로 처리.
     *
     * 응답 200 — {"exchanges": [ExchangeView…]}  최신순, 끝난 요청(수락·거절·취소)도 함께
     * (앱의 알림함 > 받은 요청 / 보낸 요청 탭)
     */
    @GetMapping
    public ExchangeDtos.ExchangeList list(@RequestParam(defaultValue = "received") String box, Authentication auth) {
        return service.list((Long) auth.getPrincipal(), box);
    }

    /**
     * ■ 교환 요청 하나   GET /api/exchanges/{id}
     * 응답 200 — ExchangeView
     * 오류: 403 NOT_PARTICIPANT(보낸 사람·받은 사람이 아님), 404 EXCHANGE_NOT_FOUND
     * (알림을 눌러 '제안 카드'를 열 때 쓴다)
     */
    @GetMapping("/{id}")
    public ExchangeDtos.ExchangeView get(@PathVariable long id, Authentication auth) {
        return service.get(id, (Long) auth.getPrincipal());
    }

    /**
     * ■ 수락   POST /api/exchanges/{id}/accept   (본문 없음)
     *   요청을 받은 사람(targetOwner)만 가능. 이 순간 교환이 '성사'된다.
     *
     * 처리 흐름 (ExchangeService.accept, 한 트랜잭션)
     *   ① 받은 사람인지(403) → ② 요청 상태 REQUESTED → ACCEPTED(아니면 409)
     *   ③ 두 물건 AVAILABLE → TRADED(이미 교환된 물건이면 409) + flush 로 @Version 충돌을 즉시 확인
     *      → 두 사람이 같은 물건 요청을 동시에 수락해도 하나만 성공(낙관적 잠금, 409 CONCURRENT_UPDATE)
     *   ④ 두 물건이 걸린 다른 대기 요청은 모두 자동 취소 + 각 요청자에게 알림
     *   ⑤ 두 사람 선호도 학습(+0.3), 채팅방에 '🎉 교환이 성사되었어요' 시스템 메시지, 요청자에게 수락 알림
     *
     * 응답 200 — ExchangeView(status: "ACCEPTED")
     * 오류: 403 NOT_RECEIVER, 404 EXCHANGE_NOT_FOUND, 409 INVALID_STATUS / ITEM_NOT_AVAILABLE / CONCURRENT_UPDATE
     */
    @PostMapping("/{id}/accept")
    public ExchangeDtos.ExchangeView accept(@PathVariable long id, Authentication auth) {
        return service.accept(id, (Long) auth.getPrincipal());
    }

    /**
     * ■ 거절   POST /api/exchanges/{id}/reject   (본문 없음) — 받은 사람만
     * 응답 200 — ExchangeView(status: "REJECTED", closeReason: "상대가 교환을 거절했어요.")
     * 오류: 403 NOT_RECEIVER, 404, 409 INVALID_STATUS(이미 처리됨)
     */
    @PostMapping("/{id}/reject")
    public ExchangeDtos.ExchangeView reject(@PathVariable long id, Authentication auth) {
        return service.reject(id, (Long) auth.getPrincipal());
    }

    /**
     * ■ 취소   POST /api/exchanges/{id}/cancel   (본문 없음) — 보낸 사람만
     * 응답 200 — ExchangeView(status: "CANCELED")
     * 오류: 403 NOT_REQUESTER, 404, 409 INVALID_STATUS
     */
    @PostMapping("/{id}/cancel")
    public ExchangeDtos.ExchangeView cancel(@PathVariable long id, Authentication auth) {
        return service.cancel(id, (Long) auth.getPrincipal());
    }
}
