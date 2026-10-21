package com.sbada.exchange;

import java.util.List;

import com.sbada.item.ItemDtos;
import com.sbada.user.UserDtos;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 교환 API 의 요청/응답 JSON 모양. 응답 예시는 ExchangeController 맨 위 주석 참고.
 *
 * [프로그래밍 설명]
 *  - 같은 교환 요청이라도 '누가 보느냐'에 따라 myRole 이 달라진다 → from(엔티티, 보는 사람 번호).
 *    앱은 myRole 로 '수락/거절' 버튼(RECEIVER)과 '취소' 버튼(REQUESTER) 중 무엇을 보여 줄지 정한다.
 */
public final class ExchangeDtos {

    private ExchangeDtos() {
    }

    /** POST /api/exchanges 요청 본문 */
    public record CreateRequest(
            @NotNull(message = "바꾸고 싶은 물건(targetItemId)이 필요합니다.") Long targetItemId,
            @NotNull(message = "내가 내놓을 물건(offeredItemId)이 필요합니다.") Long offeredItemId,
            @Size(max = 200, message = "메시지는 200자 이하입니다.") String message) {
    }

    /**
     * myRole: 이 요청에서 나의 역할 — REQUESTER(보낸 사람) · RECEIVER(받은 사람)
     * priceDiffPercent: (내놓은 물건 가격 − 원하는 물건 가격) ÷ 원하는 물건 가격 × 100
     */
    public record ExchangeView(long id, String status, String message,
                               UserDtos.PublicUser requester, UserDtos.PublicUser targetOwner,
                               ItemDtos.ItemSummary offeredItem, ItemDtos.ItemSummary targetItem,
                               int priceDiffPercent, Long chatRoomId, String closeReason,
                               String createdAt, String respondedAt, String myRole) {
        public static ExchangeView from(ExchangeRequest e, Long viewerId) {
            int target = Math.max(1, e.getTargetItem().getPrice()); // 0으로 나누기 방지
            // 100.0 으로 곱해 실수 나눗셈을 한 뒤 반올림 → (int) 로 정수 변환
            int diff = (int) Math.round((e.getOfferedItem().getPrice() - target) * 100.0 / target);
            // 중첩 삼항 연산자: 보는 사람이 없으면 null, 보낸 사람이면 REQUESTER, 받은 사람이면 RECEIVER
            String role = viewerId == null ? null
                    : e.isRequestedBy(viewerId) ? "REQUESTER" : e.isReceivedBy(viewerId) ? "RECEIVER" : null;
            return new ExchangeView(e.getId(), e.getStatus().name(), e.getMessage(),
                    UserDtos.PublicUser.from(e.getRequester()), UserDtos.PublicUser.from(e.getTargetOwner()),
                    ItemDtos.ItemSummary.from(e.getOfferedItem()), ItemDtos.ItemSummary.from(e.getTargetItem()),
                    diff, e.getChatRoomId(), e.getCloseReason(), e.getCreatedAt().toString(),
                    e.getRespondedAt() == null ? null : e.getRespondedAt().toString(), role);
        }
    }

    /** 목록 응답 {"exchanges": [...]} */
    public record ExchangeList(List<ExchangeView> exchanges) {
    }
}
