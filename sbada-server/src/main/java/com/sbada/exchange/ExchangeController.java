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
 *  2026-11-25 금융 백엔드(추가 금액 + 안전결제)
 *  POST /api/exchanges/{id}/extra         추가 금액 제안 · 요구 · 없애기 {"amount":30000,"payer":"ME|OTHER"}
 *  POST /api/exchanges/{id}/complete      '물건 받았어요'(양쪽 수령 확인) → 둘 다 누르면 받는 사람 지갑으로 정산
 *  POST /api/exchanges/{id}/refund-extra  받는 사람이 결제된 추가 금액을 돌려줌 → 환불
 *  (결제 자체는 앱 → /api/payments/escrow {"exchangeId","amount","method"} → 게이트웨이 → 결제 서비스)
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
                input.offeredItemId(), input.message(),
                input.extraAmount() == null ? 0 : input.extraAmount(), input.extraPayer());
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

    /**
     * ■ 추가 금액 정하기   POST /api/exchanges/{id}/extra   {"amount": 30000, "payer": "ME"}
     *   payer: 부르는 사람 기준 ME(내가 더 낼게요) · OTHER(상대가 더 내 주세요). amount 0 이면 조건을 없앤다.
     *   두 당사자 모두 가능, 결제 전까지만(결제 후 409 ALREADY_PAID). 상대에게 알림 + 채팅에 한 줄.
     * 오류: 400 INVALID_EXTRA_AMOUNT(1,000 ~ 1,000,000원) · 403 NOT_PARTICIPANT · 409 INVALID_STATUS / ALREADY_PAID
     */
    @PostMapping("/{id}/extra")
    public ExchangeDtos.ExchangeView extra(@PathVariable long id, @Valid @RequestBody ExchangeDtos.ExtraRequest input,
                                           Authentication auth) {
        return service.setExtra(id, (Long) auth.getPrincipal(), input.amount(), input.payer());
    }

    /**
     * ■ 물건 받았어요(양쪽 수령 확인)   POST /api/exchanges/{id}/complete   (본문 없음) — 두 당사자 각각
     *   성사(ACCEPTED)된 교환에서 물건을 받은 뒤 누른다. 한 사람만 누르면 iReceived=true 로 기다리고,
     *   둘 다 누르면 결제 서비스가 받는 사람 지갑으로 정산(paymentStatus RELEASED).
     *   아무도 안 눌러도 성사 뒤 72시간이 지나면 결제 서비스가 자동 정산한다.
     * 오류: 403 NOT_PARTICIPANT · 409 NOT_ACCEPTED / NOT_PAID / NO_EXTRA_TERMS · 503 PAYMENT_SERVICE_DOWN
     */
    @PostMapping("/{id}/complete")
    public ExchangeDtos.ExchangeView complete(@PathVariable long id, Authentication auth) {
        return service.confirmDeal(id, (Long) auth.getPrincipal());
    }

    /**
     * ■ 추가 금액 돌려주기   POST /api/exchanges/{id}/refund-extra   (본문 없음) — 추가 금액을 받을 사람만
     *   결제되어 보관 중인 돈을 낸 사람에게 환불(paymentStatus REFUNDED).
     * 오류: 403 NOT_PAYEE · 409 NOT_REFUNDABLE / ALREADY_RELEASED · 503 PAYMENT_SERVICE_DOWN
     */
    @PostMapping("/{id}/refund-extra")
    public ExchangeDtos.ExchangeView refundExtra(@PathVariable long id, Authentication auth) {
        return service.refundExtra(id, (Long) auth.getPrincipal());
    }

    // ───── 2026-11-25 사용자 요청: 수락 → 바로 수락(/accept) 또는 추가요금 요청 ─────

    /**
     * ■ 추가요금 요청   POST /api/exchanges/{id}/extra-request   (🔒 받은 사람)   {"amount":10000}
     *   물건 가격이 더 낮은 쪽(받은 사람)만 더 높은 쪽(보낸 사람)에게 요청할 수 있다. 채팅에 요청 카드가 뜬다.
     *   오류: 403 NOT_RECEIVER · 409 EXTRA_NOT_ALLOWED / EXTRA_ALREADY_REQUESTED · 400 INVALID_EXTRA_AMOUNT
     */
    @PostMapping("/{id}/extra-request")
    public ExchangeDtos.ExchangeView requestExtraFee(@PathVariable long id, @Valid @RequestBody ExchangeDtos.ExtraFeeRequest body,
                                                     Authentication auth) {
        return service.requestExtraFee(id, (Long) auth.getPrincipal(), body.amount());
    }

    /** ■ 추가요금 요청 수락   POST /api/exchanges/{id}/extra-request/accept   (🔒 보낸 사람) → 결제창으로(결제 확인되면 교환 진행) */
    @PostMapping("/{id}/extra-request/accept")
    public ExchangeDtos.ExchangeView agreeExtraFee(@PathVariable long id, Authentication auth) {
        return service.agreeExtraFee(id, (Long) auth.getPrincipal());
    }

    /** ■ 추가요금 요청 거절   POST /api/exchanges/{id}/extra-request/decline   (🔒 보낸 사람) → 받은 사람이 다시 선택 */
    @PostMapping("/{id}/extra-request/decline")
    public ExchangeDtos.ExchangeView declineExtraFee(@PathVariable long id, Authentication auth) {
        return service.declineExtraFee(id, (Long) auth.getPrincipal());
    }
}
