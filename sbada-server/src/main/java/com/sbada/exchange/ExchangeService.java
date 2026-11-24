package com.sbada.exchange;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.chat.ChatMessage;
import com.sbada.chat.ChatRoom;
import com.sbada.chat.ChatService;
import com.sbada.common.AfterCommit;
import com.sbada.common.ApiException;
import com.sbada.gateway.PaymentClient;
import com.sbada.events.EventPublisher;
import com.sbada.events.Topics;
import com.sbada.items.ItemCatalog;
import com.sbada.items.ItemRef;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.realtime.RealtimeHub;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;
import com.sbada.user.PreferenceService;

/**
 * 교환 요청 처리. 권한(403)을 먼저 확인한 뒤 상태(409)를 확인하고, 그다음에만 값을 바꾼다.
 *
 *  create : 내 물건(offered) ↔ 남의 물건(target). 같은 조합의 대기 요청이 있으면 409
 *  accept : 요청을 받은 사람만. 두 물건을 TRADED 로 바꾸고, 두 물건이 걸린 다른 대기 요청은 자동 취소
 *  reject : 요청을 받은 사람만
 *  cancel : 요청을 보낸 사람만
 *
 * [프로그래밍 설명]
 *  - 메서드마다 @Transactional → 요청 저장·물건 상태·채팅 메시지·알림 저장이 '전부 성공 또는 전부 취소'.
 *  - 실시간 전송(WebSocket)은 publish() 에서 AfterCommit 으로 미뤄, 커밋된 뒤에만 나간다.
 *  - 이 서비스는 채팅(ChatService)·알림(NotificationService)·선호도(PreferenceService)·실시간(RealtimeHub)을
 *    모두 조합하는 '오케스트레이터' 역할을 한다.
 *
 * 2026-11-25 금융 백엔드: 교환 추가 금액 + 안전결제
 *  extra        : 두 당사자 누구나 추가 금액을 제안 · 요구 · 없앨 수 있다(결제 전까지)
 *  결제          : 낸 사람이 앱 → 게이트웨이 → 결제 서비스(/api/payments/escrow)로 직접 낸다.
 *                 결제 서비스가 ESCROW_HELD 이벤트를 보내면 applyPaymentEvent 가 상태를 PAID_HELD 로 바꾼다.
 *  confirmDeal  : 성사된 교환에서 두 사람이 모두 '물건 받았어요'를 누르면(양쪽 수령 확인) 정산(release)을 부른다
 *  refundExtra  : 받는 사람이 돈을 돌려줄 때(교환이 틀어졌을 때) 환불(refund)을 부른다
 *  거절 · 취소   : 결제된 돈이 있으면 커밋 뒤 환불을 부르고 REFUND_PENDING → 이벤트가 오면 REFUNDED
 */
@Service
public class ExchangeService {

    private final ExchangeRepository exchanges;
    private final ItemCatalog items;      // 2026-11-25 MSA 2단계: 물건은 물품 서버에(배달부 gRPC)
    private final EventPublisher events;  // 허브(Kafka)로 교환 이벤트를 알린다
    private final AppUserRepository users;
    private final ChatService chat;
    private final NotificationService notifications;
    private final PreferenceService preferences;
    private final RealtimeHub hub;
    private final PaymentClient payments;

    /**
     * 시연용: 이 아이디들이 '바로 바꾸기'를 보내면 상대의 수락 없이 바로 교환 성사(sbada.demo.instant-exchange-users).
     * 발표 시연을 빠르게 하려는 예외 규칙이라 기본값은 시연 계정 admin 하나뿐이고, 비우면 모든 사람이 원래대로 '상대가 수락해야 성사'.
     */
    private final Set<String> instantExchangeUsers;

    public ExchangeService(ExchangeRepository exchanges, ItemCatalog items, EventPublisher events,
                           AppUserRepository users, ChatService chat, NotificationService notifications,
                           PreferenceService preferences, RealtimeHub hub, PaymentClient payments,
                           @Value("${sbada.demo.instant-exchange-users:}") String instantExchangeUsers) {
        this.exchanges = exchanges;
        this.items = items;
        this.events = events;
        this.users = users;
        this.chat = chat;
        this.notifications = notifications;
        this.preferences = preferences;
        this.hub = hub;
        this.payments = payments;
        this.instantExchangeUsers = Arrays.stream(instantExchangeUsers.split(","))
                .map(String::trim).filter(u -> !u.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** POST /api/exchanges — 바로 바꾸기 요청(추가 금액 없음) */
    @Transactional
    public ExchangeDtos.ExchangeView create(long userId, long targetItemId, long offeredItemId, String message) {
        return create(userId, targetItemId, offeredItemId, message, 0, null);
    }

    /**
     * POST /api/exchanges — 바로 바꾸기 요청. extraAmount > 0 이면 추가 금액 조건을 함께 건다.
     * extraPayer: 보내는 사람 기준 ME(내가 더 냄) · OTHER(상대가 더 내 주세요). 비우면 ME
     */
    @Transactional
    public ExchangeDtos.ExchangeView create(long userId, long targetItemId, long offeredItemId, String message,
                                            long extraAmount, String extraPayer) {
        checkExtraAmount(extraAmount);
        // ① 존재 확인(404) → 권한(403) → 업무 규칙(400) → 상태(409) 순서
        ItemRef target = items.require(targetItemId);   // 물품 서버에 묻는다(없거나 내려감 → 404, 꺼짐 → 503)
        ItemRef offered = items.require(offeredItemId);
        if (!offered.isOwnedBy(userId)) {
            throw ApiException.forbidden("NOT_OWNER", "내가 등록한 물건만 교환에 내놓을 수 있습니다.");
        }
        if (target.isOwnedBy(userId)) {
            throw ApiException.badRequest("SELF_EXCHANGE", "내 물건끼리는 교환할 수 없습니다.");
        }
        requireAvailable(target);    // 교환 완료된 물건이면 409 ITEM_NOT_AVAILABLE
        requireAvailable(offered);
        // ② 같은 요청을 두 번 보내지 못하게
        if (exchanges.existsBetween(offered.id(), target.id(), ExchangeStatus.REQUESTED)) {
            throw ApiException.conflict("DUPLICATE_REQUEST", "이미 같은 물건으로 교환을 요청했어요. 상대의 답을 기다려 주세요.");
        }
        // 상대가 반대 방향으로 이미 요청했다면, 새로 보내지 말고 그 요청을 수락하도록 안내
        if (exchanges.existsBetween(target.id(), offered.id(), ExchangeStatus.REQUESTED)) {
            throw ApiException.conflict("REVERSE_REQUEST_EXISTS",
                    "상대가 먼저 같은 조합으로 교환을 요청했어요. 알림에서 수락해 주세요.");
        }
        // ③ 저장
        AppUser requester = users.getReferenceById(userId);
        String text = message == null || message.isBlank() ? null : message.trim();
        AppUser owner = users.findById(target.ownerId())
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "물건 주인을 찾을 수 없습니다."));
        ExchangeRequest request = exchanges.save(new ExchangeRequest(requester, owner, offered.id(), target.id(), text));
        Map<Long, ItemRef> refs = Map.of(offered.id(), offered, target.id(), target);
        if (extraAmount > 0) {
            request.setExtraTerms(extraAmount, "OTHER".equals(extraPayer)
                    ? ExchangeRequest.PAYER_OWNER : ExchangeRequest.PAYER_REQUESTER);
        }
        // 상대 물건의 '교환 요청 수' +1(인기도)은 물품 서버의 일 → 허브(Kafka)로 알린다(같은 트랜잭션의 아웃박스)
        events.publish(Topics.EXCHANGE, request.getId(), "EXCHANGE_REQUESTED", Map.of("exchangeId", request.getId(),
                "offeredItemId", offered.id(), "targetItemId", target.id(), "requesterId", userId,
                "ownerId", target.ownerId()));

        // ④ 채팅방 연결 + 제안 카드 메시지(PROPOSAL 타입, 교환 요청 번호를 함께 저장)
        ChatRoom room = chat.ensureRoom(target, requester);
        request.linkChatRoom(room.getId());
        chat.post(room, requester, ChatMessage.Type.PROPOSAL,
                String.format("'%s'(으)로 '%s'을(를) 바꾸고 싶어요.%s%s", offered.title(), target.title(),
                        request.hasExtra() ? "\n" + extraLine(request) : "",
                        text == null ? "" : "\n" + text), request);

        // ⑤ 선호도 학습 + 받은 사람에게 알림(refType "EXCHANGE", refId = 요청 번호 → 앱이 눌렀을 때 제안 카드를 연다)
        preferences.learn(userId, target.categoryEnum(), PreferenceService.Behavior.EXCHANGE_REQUEST);
        // 시연 계정(admin 등): 상대의 수락을 기다리지 않고 같은 트랜잭션에서 바로 성사 처리
        if (instantExchangeUsers.contains(requester.getUsername())) {
            complete(request, true);
            return ExchangeDtos.ExchangeView.from(request, userId, refs);
        }
        notifications.notify(target.ownerId(), NotificationType.EXCHANGE_REQUESTED,
                "교환 요청이 왔어요",
                String.format("%s님이 '%s'(으)로 '%s'을(를) 바꾸고 싶어해요. 수락하면 교환이 완료돼요.",
                        requester.getNickname(), offered.title(), target.title()),
                "EXCHANGE", request.getId());
        // ⑥ 두 사람 화면 갱신 이벤트(커밋 후)
        publish(request, refs);
        return ExchangeDtos.ExchangeView.from(request, userId, refs);
    }

    /** POST /api/exchanges/{id}/accept — 수락(교환 성사) */
    @Transactional
    public ExchangeDtos.ExchangeView accept(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        if (!request.isReceivedBy(userId)) {
            throw ApiException.forbidden("NOT_RECEIVER", "요청을 받은 사람만 수락할 수 있습니다.");
        }
        complete(request, false);
        return view(request, userId);
    }

    /**
     * 교환 성사 처리(수락 · 시연 계정의 바로 성사 공통): 상태 ACCEPTED · 두 물건 TRADED · 겹친 요청 자동 취소 · 선호도 · 알림.
     * instant = true 면 받은 사람이 직접 수락한 게 아니므로, 요청자 대신 받은 사람에게 '바로 성사' 알림을 보낸다.
     */
    private void complete(ExchangeRequest request, boolean instant) {
        long receiverId = request.getTargetOwner().getId();
        request.accept();          // REQUESTED 가 아니면 409
        // 2026-11-25 MSA 2단계: 두 물건을 거래 완료로 바꾸는 일은 물품 서버가 한 트랜잭션에서(배달부 gRPC CompleteTrade).
        //  하나라도 이미 교환 · 내려감이면 409 → 이 트랜잭션(수락)도 취소된다.
        //  (gRPC 성공 뒤 이 트랜잭션이 실패하는 드문 경우는 물건만 TRADED 로 남는다 — 사가 보상은 다음 단계 과제)
        items.completeTrade(request.getId(), request.getOfferedItemId(), request.getTargetItemId());

        // 두 물건이 걸린 '다른' 대기 요청은 더 이상 이룰 수 없으므로 자동 취소 + 그 요청자에게 알림
        List<ExchangeRequest> others = exchanges.findInvolving(
                List.of(request.getOfferedItemId(), request.getTargetItemId()), ExchangeStatus.REQUESTED);
        java.util.Set<Long> ids = new java.util.HashSet<>(List.of(request.getOfferedItemId(), request.getTargetItemId()));
        others.forEach(o -> ids.add(o.getTargetItemId()));
        Map<Long, ItemRef> refs = items.get(ids);
        ItemRef offered = ref(refs, request.getOfferedItemId());
        ItemRef target = ref(refs, request.getTargetItemId());
        for (ExchangeRequest other : others) {
            if (other.getId().equals(request.getId())) { // Long 끼리는 == 가 아니라 equals 로 값 비교
                continue;
            }
            other.cancel("물건이 다른 교환으로 거래 완료되어 자동 취소되었어요.");
            refundIfPaid(other, "물건이 다른 교환으로 거래 완료되어");
            notifications.notify(other.getRequester().getId(), NotificationType.EXCHANGE_CANCELED,
                    "교환 요청이 취소되었어요",
                    String.format("'%s'이(가) 다른 교환으로 거래 완료되어 요청이 자동 취소되었어요.",
                            ref(refs, other.getTargetItemId()).title()),
                    "EXCHANGE", other.getId());
            publish(other, refs);
        }

        // 교환 성사는 가장 강한 관심 신호(+0.3): 요청자는 받은 물건 종류를, 수락자는 받은 물건 종류를 더 좋아한다고 학습
        preferences.learn(request.getRequester().getId(), target.categoryEnum(), PreferenceService.Behavior.TRADE);
        preferences.learn(receiverId, offered.categoryEnum(), PreferenceService.Behavior.TRADE);
        events.publish(Topics.EXCHANGE, request.getId(), "EXCHANGE_ACCEPTED", Map.of("exchangeId", request.getId(),
                "offeredItemId", request.getOfferedItemId(), "targetItemId", request.getTargetItemId()));
        postSystem(request, String.format("🎉 교환이 성사되었어요! '%s' ↔ '%s'", offered.title(), target.title()));
        if (instant) {
            notifications.notify(receiverId, NotificationType.EXCHANGE_ACCEPTED,
                    "교환이 성사되었어요 🎉",
                    String.format("%s님과 '%s' ↔ '%s' 교환이 바로 성사되었어요(시연 계정). 채팅으로 만날 약속을 정해 보세요.",
                            request.getRequester().getNickname(), offered.title(), target.title()),
                    "EXCHANGE", request.getId());
        } else {
            notifications.notify(request.getRequester().getId(), NotificationType.EXCHANGE_ACCEPTED,
                    "교환이 성사되었어요 🎉",
                    String.format("%s님이 '%s' ↔ '%s' 교환을 수락했어요. 채팅으로 만날 약속을 정해 보세요.",
                            request.getTargetOwner().getNickname(), offered.title(), target.title()),
                    "EXCHANGE", request.getId());
        }
        publish(request);
    }

    /** POST /api/exchanges/{id}/reject — 거절 */
    @Transactional
    public ExchangeDtos.ExchangeView reject(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        if (!request.isReceivedBy(userId)) {
            throw ApiException.forbidden("NOT_RECEIVER", "요청을 받은 사람만 거절할 수 있습니다.");
        }
        request.reject();
        postSystem(request, "교환 요청이 거절되었어요.");
        refundIfPaid(request, "교환이 거절되어");
        notifications.notify(request.getRequester().getId(), NotificationType.EXCHANGE_REJECTED,
                "교환 요청이 거절되었어요",
                String.format("'%s' 교환 요청이 거절되었어요. 비슷한 가격대의 다른 물건을 찾아볼까요?",
                        itemTitle(request.getTargetItemId())),
                "EXCHANGE", request.getId());
        publish(request);
        return view(request, userId);
    }

    /** POST /api/exchanges/{id}/cancel — 보낸 사람이 취소 */
    @Transactional
    public ExchangeDtos.ExchangeView cancel(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        if (!request.isRequestedBy(userId)) {
            throw ApiException.forbidden("NOT_REQUESTER", "요청을 보낸 사람만 취소할 수 있습니다.");
        }
        request.cancel("요청한 사람이 취소했어요.");
        postSystem(request, "교환 요청이 취소되었어요.");
        refundIfPaid(request, "교환이 취소되어");
        notifications.notify(request.getTargetOwner().getId(), NotificationType.EXCHANGE_CANCELED,
                "교환 요청이 취소되었어요",
                String.format("%s님이 '%s' 교환 요청을 취소했어요.", request.getRequester().getNickname(),
                        itemTitle(request.getTargetItemId())),
                "EXCHANGE", request.getId());
        publish(request);
        return view(request, userId);
    }

    /**
     * 물건이 내려가거나(삭제) · 보류되거나 · 다른 교환으로 거래 완료되면 그 물건이 걸린 대기 요청을 모두 취소한다.
     * 2026-11-25 MSA 2단계: 물품 서버의 허브(Kafka) 이벤트 ITEM_STATUS_CHANGED 를 받아 MainEventHandler 가 부른다.
     */
    @Transactional
    public void cancelAllFor(long itemId, long ownerId, String title, String reason) {
        String name = title == null || title.isBlank() ? "물건" : title;
        String why = reason == null || reason.isBlank() ? "물건이 내려가서 교환 요청이 취소되었어요." : reason;
        for (ExchangeRequest other : exchanges.findInvolving(List.of(itemId), ExchangeStatus.REQUESTED)) {
            other.cancel(why.length() > 120 ? why.substring(0, 120) : why);
            refundIfPaid(other, "물건이 내려가서");
            // 알림 대상 = 물건 주인이 아닌 쪽(주인이 보낸 요청이면 받은 사람에게, 아니면 보낸 사람에게)
            long notifyUser = other.getRequester().getId() == ownerId
                    ? other.getTargetOwner().getId() : other.getRequester().getId();
            notifications.notify(notifyUser, NotificationType.EXCHANGE_CANCELED, "교환 요청이 취소되었어요",
                    String.format("'%s'이(가) 내려가서 요청이 취소되었어요.", name),
                    "EXCHANGE", other.getId());
            publish(other);
        }
    }

    /** GET /api/exchanges/{id} — 당사자만 */
    @Transactional(readOnly = true)
    public ExchangeDtos.ExchangeView get(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        if (!request.isRequestedBy(userId) && !request.isReceivedBy(userId)) {
            throw ApiException.forbidden("NOT_PARTICIPANT", "교환 당사자만 볼 수 있습니다.");
        }
        return view(request, userId);
    }

    /** GET /api/exchanges?box= — "sent" 가 아니면 모두 받은 요청으로 처리 */
    @Transactional(readOnly = true)
    public ExchangeDtos.ExchangeList list(long userId, String box) {
        List<ExchangeRequest> list = "sent".equalsIgnoreCase(box) ? exchanges.findSent(userId)
                : exchanges.findReceived(userId);
        java.util.Set<Long> ids = new java.util.HashSet<>();
        list.forEach(e -> {
            ids.add(e.getOfferedItemId());
            ids.add(e.getTargetItemId());
        });
        Map<Long, ItemRef> refs = items.get(ids); // 물건 요약을 한 번에(물건마다 묻지 않는다)
        return new ExchangeDtos.ExchangeList(list.stream().map(e -> ExchangeDtos.ExchangeView.from(e, userId, refs)).toList());
    }

    // ───────────── 2026-11-25 추가 금액 · 안전결제 ─────────────

    /** POST /api/exchanges/{id}/extra — 추가 금액 제안 · 요구 · 없애기(0원). 결제 전까지 두 당사자 모두 가능 */
    @Transactional
    public ExchangeDtos.ExchangeView setExtra(long requestId, long userId, long amount, String payer) {
        ExchangeRequest request = require(requestId);
        boolean requester = request.isRequestedBy(userId);
        if (!requester && !request.isReceivedBy(userId)) {
            throw ApiException.forbidden("NOT_PARTICIPANT", "교환 당사자만 추가 금액을 정할 수 있습니다.");
        }
        checkExtraAmount(amount);
        boolean iPay = !"OTHER".equals(payer);
        // 부르는 사람 기준(ME · OTHER) → 저장 기준(REQUESTER · OWNER)
        String who = requester == iPay ? ExchangeRequest.PAYER_REQUESTER : ExchangeRequest.PAYER_OWNER;
        request.setExtraTerms(amount, who); // 끝난 교환 · 이미 결제됨 → 409
        AppUser me = requester ? request.getRequester() : request.getTargetOwner();
        long otherId = requester ? request.getTargetOwner().getId() : request.getRequester().getId();
        String line = request.hasExtra() ? extraLine(request) : "추가 금액 조건을 없앴어요.";
        postSystem(request, "💰 " + me.getNickname() + "님: " + line);
        notifications.notify(otherId, NotificationType.EXCHANGE_REQUESTED,
                request.hasExtra() ? (iPay ? "추가 금액 제안이 왔어요" : "추가 금액 요청이 왔어요") : "추가 금액 조건이 없어졌어요",
                String.format("'%s' 교환 — %s", itemTitle(request.getTargetItemId()), line),
                "EXCHANGE", request.getId());
        publish(request);
        return view(request, userId);
    }

    /**
     * POST /api/exchanges/{id}/complete — '물건 받았어요'(양쪽 수령 확인). 두 당사자가 각각 누른다.
     *  한 사람만 누르면 상대에게 알림 + 채팅 한 줄, 둘 다 누르면 결제 서비스에 정산을 부른다 → 받는 사람 지갑으로.
     *  결제 서비스가 꺼져 있으면 503 이고 이 트랜잭션도 취소된다(다시 누르면 됨).
     *  아무도 안 눌러도 성사 72시간 뒤 결제 서비스가 자동 정산한다(가상 거래 · 실제 돈 이동 없음).
     */
    @Transactional
    public ExchangeDtos.ExchangeView confirmDeal(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        boolean requester = request.isRequestedBy(userId);
        if (!requester && !request.isReceivedBy(userId)) {
            throw ApiException.forbidden("NOT_PARTICIPANT", "교환 당사자만 수령 확인을 할 수 있어요.");
        }
        if (!request.hasExtra()) {
            throw ApiException.conflict("NO_EXTRA_TERMS", "이 교환에는 추가 금액이 없어요.");
        }
        if (request.getStatus() != ExchangeStatus.ACCEPTED) {
            throw ApiException.conflict("NOT_ACCEPTED", "교환이 성사된 뒤에 수령 확인을 할 수 있어요.");
        }
        if (request.payment() == PaymentStatus.RELEASED) {
            return view(request, userId); // 이미 정산됨(두 번 눌러도 안전)
        }
        if (request.payment() != PaymentStatus.PAID_HELD) {
            throw ApiException.conflict("NOT_PAID", "아직 추가 금액이 결제되지 않았어요.");
        }
        if (request.confirmReceived(userId)) {
            AppUser me = requester ? request.getRequester() : request.getTargetOwner();
            long otherId = requester ? request.getTargetOwner().getId() : request.getRequester().getId();
            if (!request.bothReceived()) {
                postSystem(request, "📦 " + me.getNickname() + "님이 물건을 받았어요. (수령 확인 1/2)");
                notifications.notify(otherId, NotificationType.EXCHANGE_ACCEPTED, "상대가 물건을 받았어요",
                        String.format("'%s' 교환 — 물건을 받았으면 '물건 받았어요'를 눌러 주세요. 둘 다 누르면 추가 금액이 정산돼요.",
                                itemTitle(request.getTargetItemId())),
                        "EXCHANGE", request.getId());
                publish(request);
                return view(request, userId);
            }
            postSystem(request, "📦 " + me.getNickname() + "님도 물건을 받았어요. (수령 확인 2/2)");
        }
        if (request.bothReceived()) {
            payments.release(request.getId()); // 실패하면 ApiException(503 · 409) → 이 트랜잭션도 취소
            markPayment(request, PaymentStatus.RELEASED);
        }
        return view(request, userId);
    }

    /** POST /api/exchanges/{id}/refund-extra — 받는 사람이 결제된 추가 금액을 돌려준다(교환이 틀어졌을 때) */
    @Transactional
    public ExchangeDtos.ExchangeView refundExtra(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        if (!request.hasExtra() || !Long.valueOf(userId).equals(request.payeeId())) {
            throw ApiException.forbidden("NOT_PAYEE", "추가 금액을 받을 사람만 돌려줄 수 있어요.");
        }
        if (request.payment() == PaymentStatus.REFUNDED) {
            return view(request, userId);
        }
        if (request.payment() != PaymentStatus.PAID_HELD && request.payment() != PaymentStatus.REFUND_PENDING) {
            throw ApiException.conflict("NOT_REFUNDABLE", "돌려줄 수 있는 결제가 없어요(현재: " + request.getPaymentStatus() + ").");
        }
        payments.refund(request.getId(), "상대가 추가 금액을 돌려줘서");
        markPayment(request, PaymentStatus.REFUNDED);
        return view(request, userId);
    }

    /**
     * 결제 서비스 이벤트 → 교환의 결제 상태 반영(InternalController 가 부름, 같은 트랜잭션).
     * 낸 사람 · 받는 사람에게 이벤트가 하나씩 오므로 두 번 불릴 수 있다 → 바뀐 경우에만 채팅 · 화면 갱신.
     */
    @Transactional
    public void applyPaymentEvent(long exchangeId, String eventType) {
        Optional<ExchangeRequest> found = exchanges.findById(exchangeId);
        if (found.isEmpty()) {
            return;
        }
        ExchangeRequest request = found.get();
        switch (eventType) {
            case "ESCROW_HELD" -> {
                if (request.getStatus() == ExchangeStatus.REJECTED || request.getStatus() == ExchangeStatus.CANCELED) {
                    // 결제와 취소가 엇갈린 경우: 바로 환불을 건다(결제 서비스 대사 작업도 같은 일을 한다)
                    if (request.changePayment(PaymentStatus.REFUND_PENDING)) {
                        AfterCommit.run(() -> payments.refundQuietly(exchangeId, "교환이 이미 끝나서"));
                        publish(request);
                    }
                    return;
                }
                if (request.payment() == PaymentStatus.NONE || request.payment() == PaymentStatus.REFUNDED) {
                    markPayment(request, PaymentStatus.PAID_HELD);
                }
            }
            case "ESCROW_RELEASED" -> markPayment(request, PaymentStatus.RELEASED);
            case "ESCROW_REFUNDED" -> markPayment(request, PaymentStatus.REFUNDED);
            default -> {
                // 그 밖의 지갑 이벤트는 교환 상태와 상관없음
            }
        }
    }

    /** GET /internal/exchanges/{id} — 결제 서비스가 '누가 얼마를 누구에게' 낼지 확인할 때 쓴다 */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> internalInfo(long exchangeId) {
        return exchanges.findById(exchangeId).map(e -> {
            Map<String, Object> m = new LinkedHashMap<>(); // payerId 가 null 일 수 있어 Map.of 대신
            m.put("id", e.getId());
            m.put("status", e.getStatus().name());
            m.put("requesterId", e.getRequester().getId());
            m.put("ownerId", e.getTargetOwner().getId());
            m.put("extraAmount", e.getExtraAmount());
            m.put("payerId", e.payerId());
            m.put("payeeId", e.payeeId());
            m.put("paymentStatus", e.getPaymentStatus());
            return m;
        });
    }

    /** 상태를 바꾸고, 실제로 바뀌었으면 채팅에 한 줄 + 두 사람 화면 갱신 */
    private void markPayment(ExchangeRequest request, PaymentStatus next) {
        if (!request.changePayment(next)) {
            return;
        }
        String amount = won(request.getExtraAmount());
        String text = switch (next) {
            case PAID_HELD -> "💳 추가 금액 " + amount + "이 결제되었어요. 교환을 마치면 상대에게 전달돼요.";
            case RELEASED -> "✅ 거래 완료! 추가 금액 " + amount + "이 지갑으로 전달되었어요.";
            case REFUND_PENDING -> "↩️ 추가 금액 " + amount + " 환불을 요청했어요.";
            case REFUNDED -> "↩️ 추가 금액 " + amount + "이 환불되었어요.";
            case NONE -> null;
        };
        if (text != null) {
            postSystem(request, text);
        }
        publish(request);
    }

    /** 거절 · 취소 때: 결제되어 보관 중인 돈이 있으면 커밋 뒤 환불 요청(실패해도 결제 서비스가 나중에 다시 처리) */
    private void refundIfPaid(ExchangeRequest request, String reason) {
        if (request.payment() != PaymentStatus.PAID_HELD) {
            return;
        }
        markPayment(request, PaymentStatus.REFUND_PENDING);
        long id = request.getId();
        AfterCommit.run(() -> payments.refundQuietly(id, reason));
    }

    /** 0원(없음) 또는 1,000원 ~ 1,000,000원 (결제 서비스와 같은 범위) */
    private static void checkExtraAmount(long amount) {
        if (amount != 0 && (amount < 1_000 || amount > 1_000_000)) {
            throw ApiException.badRequest("INVALID_EXTRA_AMOUNT", "추가 금액은 1,000원 이상 1,000,000원 이하로 정해 주세요.");
        }
    }

    /** 채팅 · 알림 문구: "준호님이 민지님에게 30,000원을 더 내요." */
    private static String extraLine(ExchangeRequest r) {
        boolean requesterPays = ExchangeRequest.PAYER_REQUESTER.equals(r.getExtraPayer());
        AppUser payer = requesterPays ? r.getRequester() : r.getTargetOwner();
        AppUser payee = requesterPays ? r.getTargetOwner() : r.getRequester();
        return String.format("%s님이 %s님에게 %s을 더 내요. (안전결제 · 수수료 5%%는 내는 사람 부담)",
                payer.getNickname(), payee.getNickname(), won(r.getExtraAmount()));
    }

    private static String won(long amount) {
        return NumberFormat.getNumberInstance(Locale.KOREA).format(amount) + "원";
    }

    /** 채팅방에 시스템 메시지(보낸 사람 없음)를 남긴다. 채팅방이 없는 오래된 요청이면 건너뜀. */
    private void postSystem(ExchangeRequest request, String content) {
        if (request.getChatRoomId() == null) {
            return;
        }
        ChatRoom room = chat.roomOf(request.getChatRoomId());
        if (room != null) {
            chat.post(room, null, ChatMessage.Type.SYSTEM, content, request);
        }
    }

    /**
     * 두 당사자의 화면(제안 카드·알림함)이 바로 바뀌도록 실시간 이벤트를 보낸다.
     * WebSocket 메시지 예: {"type":"exchange.updated","exchange":{ExchangeView…}}
     * 사람마다 myRole 이 다르므로 각자용 DTO 를 따로 만들고, 실제 전송은 커밋 후(AfterCommit)에 한다.
     * 람다 안에서 쓰는 지역 변수(requesterId 등)는 값이 바뀌지 않아야 한다(effectively final).
     */
    private void publish(ExchangeRequest request) {
        publish(request, items.get(List.of(request.getOfferedItemId(), request.getTargetItemId())));
    }

    private void publish(ExchangeRequest request, Map<Long, ItemRef> refs) {
        long requesterId = request.getRequester().getId();
        long ownerId = request.getTargetOwner().getId();
        ExchangeDtos.ExchangeView forRequester = ExchangeDtos.ExchangeView.from(request, requesterId, refs);
        ExchangeDtos.ExchangeView forOwner = ExchangeDtos.ExchangeView.from(request, ownerId, refs);
        AfterCommit.run(() -> {
            hub.sendToUser(requesterId, Map.of("type", "exchange.updated", "exchange", forRequester));
            hub.sendToUser(ownerId, Map.of("type", "exchange.updated", "exchange", forOwner));
        });
    }

    private ExchangeRequest require(long id) {
        return exchanges.findById(id)
                .orElseThrow(() -> ApiException.notFound("EXCHANGE_NOT_FOUND", "교환 요청을 찾을 수 없습니다."));
    }

    /** 교환 카드 한 장(물건 요약은 물품 서버에서) */
    private ExchangeDtos.ExchangeView view(ExchangeRequest r, Long viewerId) {
        return ExchangeDtos.ExchangeView.from(r, viewerId, items.get(List.of(r.getOfferedItemId(), r.getTargetItemId())));
    }

    private static ItemRef ref(Map<Long, ItemRef> refs, long id) {
        return refs.getOrDefault(id, ItemRef.unknown(id));
    }

    /** 알림 문구용 물건 이름(물품 서버가 꺼져 있으면 '물건') */
    private String itemTitle(long itemId) {
        ItemRef r = items.get(List.of(itemId)).get(itemId);
        return r == null ? "물건" : r.title();
    }

    /** 교환할 수 있는 물건인지(예전 Item.requireAvailable 과 같은 오류) */
    private static void requireAvailable(ItemRef item) {
        if (!item.isAvailable()) {
            throw ApiException.conflict("ITEM_NOT_AVAILABLE", "이미 교환되었거나 내려간 물건입니다.");
        }
    }
}
