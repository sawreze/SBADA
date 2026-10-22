package com.sbada.exchange;

import java.util.Arrays;
import java.util.List;
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
import com.sbada.item.Item;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;
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
 */
@Service
public class ExchangeService {

    private final ExchangeRepository exchanges;
    private final ItemRepository items;
    private final AppUserRepository users;
    private final ChatService chat;
    private final NotificationService notifications;
    private final PreferenceService preferences;
    private final RealtimeHub hub;

    /**
     * 시연용: 이 아이디들이 '바로 바꾸기'를 보내면 상대의 수락 없이 바로 교환 성사(sbada.demo.instant-exchange-users).
     * 발표 시연을 빠르게 하려는 예외 규칙이라 기본값은 시연 계정 admin 하나뿐이고, 비우면 모든 사람이 원래대로 '상대가 수락해야 성사'.
     */
    private final Set<String> instantExchangeUsers;

    public ExchangeService(ExchangeRepository exchanges, ItemRepository items, AppUserRepository users,
                           ChatService chat, NotificationService notifications, PreferenceService preferences,
                           RealtimeHub hub,
                           @Value("${sbada.demo.instant-exchange-users:}") String instantExchangeUsers) {
        this.exchanges = exchanges;
        this.items = items;
        this.users = users;
        this.chat = chat;
        this.notifications = notifications;
        this.preferences = preferences;
        this.hub = hub;
        this.instantExchangeUsers = Arrays.stream(instantExchangeUsers.split(","))
                .map(String::trim).filter(u -> !u.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** POST /api/exchanges — 바로 바꾸기 요청 */
    @Transactional
    public ExchangeDtos.ExchangeView create(long userId, long targetItemId, long offeredItemId, String message) {
        // ① 존재 확인(404) → 권한(403) → 업무 규칙(400) → 상태(409) 순서
        Item target = requireItem(targetItemId);
        Item offered = requireItem(offeredItemId);
        if (!offered.isOwnedBy(userId)) {
            throw ApiException.forbidden("NOT_OWNER", "내가 등록한 물건만 교환에 내놓을 수 있습니다.");
        }
        if (target.isOwnedBy(userId)) {
            throw ApiException.badRequest("SELF_EXCHANGE", "내 물건끼리는 교환할 수 없습니다.");
        }
        target.requireAvailable();   // 교환 완료된 물건이면 409 ITEM_NOT_AVAILABLE
        offered.requireAvailable();
        // ② 같은 요청을 두 번 보내지 못하게
        if (exchanges.existsBetween(offered.getId(), target.getId(), ExchangeStatus.REQUESTED)) {
            throw ApiException.conflict("DUPLICATE_REQUEST", "이미 같은 물건으로 교환을 요청했어요. 상대의 답을 기다려 주세요.");
        }
        // 상대가 반대 방향으로 이미 요청했다면, 새로 보내지 말고 그 요청을 수락하도록 안내
        if (exchanges.existsBetween(target.getId(), offered.getId(), ExchangeStatus.REQUESTED)) {
            throw ApiException.conflict("REVERSE_REQUEST_EXISTS",
                    "상대가 먼저 같은 조합으로 교환을 요청했어요. 알림에서 수락해 주세요.");
        }
        // ③ 저장
        AppUser requester = users.getReferenceById(userId);
        String text = message == null || message.isBlank() ? null : message.trim();
        ExchangeRequest request = exchanges.save(new ExchangeRequest(requester, offered, target, text));
        target.increaseRequest();    // 상대 물건의 '교환 요청 수' +1 (인기도 점수에 쓰임)

        // ④ 채팅방 연결 + 제안 카드 메시지(PROPOSAL 타입, 교환 요청 번호를 함께 저장)
        ChatRoom room = chat.ensureRoom(target, requester);
        request.linkChatRoom(room.getId());
        chat.post(room, requester, ChatMessage.Type.PROPOSAL,
                String.format("'%s'(으)로 '%s'을(를) 바꾸고 싶어요.%s", offered.getTitle(), target.getTitle(),
                        text == null ? "" : "\n" + text), request);

        // ⑤ 선호도 학습 + 받은 사람에게 알림(refType "EXCHANGE", refId = 요청 번호 → 앱이 눌렀을 때 제안 카드를 연다)
        preferences.learn(userId, target.getCategory(), PreferenceService.Behavior.EXCHANGE_REQUEST);
        // 시연 계정(admin 등): 상대의 수락을 기다리지 않고 같은 트랜잭션에서 바로 성사 처리
        if (instantExchangeUsers.contains(requester.getUsername())) {
            complete(request, true);
            return ExchangeDtos.ExchangeView.from(request, userId);
        }
        notifications.notify(target.getOwner().getId(), NotificationType.EXCHANGE_REQUESTED,
                "교환 요청이 왔어요",
                String.format("%s님이 '%s'(으)로 '%s'을(를) 바꾸고 싶어해요. 수락하면 교환이 완료돼요.",
                        requester.getNickname(), offered.getTitle(), target.getTitle()),
                "EXCHANGE", request.getId());
        // ⑥ 두 사람 화면 갱신 이벤트(커밋 후)
        publish(request);
        return ExchangeDtos.ExchangeView.from(request, userId);
    }

    /** POST /api/exchanges/{id}/accept — 수락(교환 성사) */
    @Transactional
    public ExchangeDtos.ExchangeView accept(long requestId, long userId) {
        ExchangeRequest request = require(requestId);
        if (!request.isReceivedBy(userId)) {
            throw ApiException.forbidden("NOT_RECEIVER", "요청을 받은 사람만 수락할 수 있습니다.");
        }
        complete(request, false);
        return ExchangeDtos.ExchangeView.from(request, userId);
    }

    /**
     * 교환 성사 처리(수락 · 시연 계정의 바로 성사 공통): 상태 ACCEPTED · 두 물건 TRADED · 겹친 요청 자동 취소 · 선호도 · 알림.
     * instant = true 면 받은 사람이 직접 수락한 게 아니므로, 요청자 대신 받은 사람에게 '바로 성사' 알림을 보낸다.
     */
    private void complete(ExchangeRequest request, boolean instant) {
        long receiverId = request.getTargetOwner().getId();
        Item offered = request.getOfferedItem();
        Item target = request.getTargetItem();
        request.accept();          // REQUESTED 가 아니면 409
        offered.markTraded();      // AVAILABLE 이 아니면 409
        target.markTraded();
        // flush(): 지금까지의 변경을 커밋 전에 DB 로 즉시 보낸다(UPDATE 실행).
        // Item 의 @Version 이 다른 요청과 부딪히면 여기서 예외가 나서 409 CONCURRENT_UPDATE 로 응답된다.
        items.flush();             // @Version 충돌을 이 요청 안에서 409 로 돌려주기 위해 바로 반영

        // 두 물건이 걸린 '다른' 대기 요청은 더 이상 이룰 수 없으므로 자동 취소 + 그 요청자에게 알림
        for (ExchangeRequest other : exchanges.findInvolving(List.of(offered.getId(), target.getId()),
                ExchangeStatus.REQUESTED)) {
            if (other.getId().equals(request.getId())) { // Long 끼리는 == 가 아니라 equals 로 값 비교
                continue;
            }
            other.cancel("물건이 다른 교환으로 거래 완료되어 자동 취소되었어요.");
            notifications.notify(other.getRequester().getId(), NotificationType.EXCHANGE_CANCELED,
                    "교환 요청이 취소되었어요",
                    String.format("'%s'이(가) 다른 교환으로 거래 완료되어 요청이 자동 취소되었어요.",
                            other.getTargetItem().getTitle()),
                    "EXCHANGE", other.getId());
            publish(other);
        }

        // 교환 성사는 가장 강한 관심 신호(+0.3): 요청자는 받은 물건 종류를, 수락자는 받은 물건 종류를 더 좋아한다고 학습
        preferences.learn(request.getRequester().getId(), target.getCategory(), PreferenceService.Behavior.TRADE);
        preferences.learn(receiverId, offered.getCategory(), PreferenceService.Behavior.TRADE);
        postSystem(request, String.format("🎉 교환이 성사되었어요! '%s' ↔ '%s'", offered.getTitle(), target.getTitle()));
        if (instant) {
            notifications.notify(receiverId, NotificationType.EXCHANGE_ACCEPTED,
                    "교환이 성사되었어요 🎉",
                    String.format("%s님과 '%s' ↔ '%s' 교환이 바로 성사되었어요(시연 계정). 채팅으로 만날 약속을 정해 보세요.",
                            request.getRequester().getNickname(), offered.getTitle(), target.getTitle()),
                    "EXCHANGE", request.getId());
        } else {
            notifications.notify(request.getRequester().getId(), NotificationType.EXCHANGE_ACCEPTED,
                    "교환이 성사되었어요 🎉",
                    String.format("%s님이 '%s' ↔ '%s' 교환을 수락했어요. 채팅으로 만날 약속을 정해 보세요.",
                            request.getTargetOwner().getNickname(), offered.getTitle(), target.getTitle()),
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
        notifications.notify(request.getRequester().getId(), NotificationType.EXCHANGE_REJECTED,
                "교환 요청이 거절되었어요",
                String.format("'%s' 교환 요청이 거절되었어요. 비슷한 가격대의 다른 물건을 찾아볼까요?",
                        request.getTargetItem().getTitle()),
                "EXCHANGE", request.getId());
        publish(request);
        return ExchangeDtos.ExchangeView.from(request, userId);
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
        notifications.notify(request.getTargetOwner().getId(), NotificationType.EXCHANGE_CANCELED,
                "교환 요청이 취소되었어요",
                String.format("%s님이 '%s' 교환 요청을 취소했어요.", request.getRequester().getNickname(),
                        request.getTargetItem().getTitle()),
                "EXCHANGE", request.getId());
        publish(request);
        return ExchangeDtos.ExchangeView.from(request, userId);
    }

    /** 물건을 삭제(숨김)할 때 그 물건이 걸린 대기 요청을 모두 취소한다. (ItemService.delete 가 같은 트랜잭션에서 부름) */
    @Transactional
    public void cancelAllFor(Item item, String reason) {
        for (ExchangeRequest other : exchanges.findInvolving(List.of(item.getId()), ExchangeStatus.REQUESTED)) {
            other.cancel(reason);
            // 알림 대상 = 물건 주인이 아닌 쪽(주인이 보낸 요청이면 받은 사람에게, 아니면 보낸 사람에게)
            long notifyUser = other.getRequester().getId().equals(item.getOwner().getId())
                    ? other.getTargetOwner().getId() : other.getRequester().getId();
            notifications.notify(notifyUser, NotificationType.EXCHANGE_CANCELED, "교환 요청이 취소되었어요",
                    String.format("'%s'이(가) 내려가서 요청이 취소되었어요.", item.getTitle()),
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
        return ExchangeDtos.ExchangeView.from(request, userId);
    }

    /** GET /api/exchanges?box= — "sent" 가 아니면 모두 받은 요청으로 처리 */
    @Transactional(readOnly = true)
    public ExchangeDtos.ExchangeList list(long userId, String box) {
        List<ExchangeRequest> list = "sent".equalsIgnoreCase(box) ? exchanges.findSent(userId)
                : exchanges.findReceived(userId);
        return new ExchangeDtos.ExchangeList(list.stream().map(e -> ExchangeDtos.ExchangeView.from(e, userId)).toList());
    }

    /** 채팅방에 시스템 메시지(보낸 사람 없음)를 남긴다. 채팅방이 없는 오래된 요청이면 건너뜀. */
    private void postSystem(ExchangeRequest request, String content) {
        if (request.getChatRoomId() == null) {
            return;
        }
        ChatRoom room = chat.ensureRoom(request.getTargetItem(), request.getRequester());
        chat.post(room, null, ChatMessage.Type.SYSTEM, content, request);
    }

    /**
     * 두 당사자의 화면(제안 카드·알림함)이 바로 바뀌도록 실시간 이벤트를 보낸다.
     * WebSocket 메시지 예: {"type":"exchange.updated","exchange":{ExchangeView…}}
     * 사람마다 myRole 이 다르므로 각자용 DTO 를 따로 만들고, 실제 전송은 커밋 후(AfterCommit)에 한다.
     * 람다 안에서 쓰는 지역 변수(requesterId 등)는 값이 바뀌지 않아야 한다(effectively final).
     */
    private void publish(ExchangeRequest request) {
        long requesterId = request.getRequester().getId();
        long ownerId = request.getTargetOwner().getId();
        ExchangeDtos.ExchangeView forRequester = ExchangeDtos.ExchangeView.from(request, requesterId);
        ExchangeDtos.ExchangeView forOwner = ExchangeDtos.ExchangeView.from(request, ownerId);
        AfterCommit.run(() -> {
            hub.sendToUser(requesterId, Map.of("type", "exchange.updated", "exchange", forRequester));
            hub.sendToUser(ownerId, Map.of("type", "exchange.updated", "exchange", forOwner));
        });
    }

    private ExchangeRequest require(long id) {
        return exchanges.findById(id)
                .orElseThrow(() -> ApiException.notFound("EXCHANGE_NOT_FOUND", "교환 요청을 찾을 수 없습니다."));
    }

    /** 물건 찾기(내린 물건은 없는 것으로) */
    private Item requireItem(long id) {
        return items.findById(id)
                .filter(i -> i.getStatus() != ItemStatus.HIDDEN)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다."));
    }
}
