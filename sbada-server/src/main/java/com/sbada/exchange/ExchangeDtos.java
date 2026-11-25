package com.sbada.exchange;

import java.util.List;

import com.sbada.items.ItemRef;
import com.sbada.user.UserDtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
            @Size(max = 200, message = "메시지는 200자 이하입니다.") String message,
            // 2026-11-25 선택: 추가 금액(0 · 없음 = 없음). extraPayer 는 보내는 사람 기준 ME(내가 냄) · OTHER(상대가 냄)
            @Min(value = 0, message = "추가 금액은 0원 이상입니다.")
            @Max(value = 1_000_000, message = "추가 금액은 1,000,000원 이하입니다.") Long extraAmount,
            @Pattern(regexp = "ME|OTHER", message = "extraPayer 는 ME 또는 OTHER 입니다.") String extraPayer) {
    }

    /**
     * POST /api/exchanges/{id}/extra — 추가 금액 제안 · 요구 · 없애기(amount 0). 두 당사자 모두 가능, 결제 전까지만.
     *  payer: 부르는 사람 기준 ME(내가 더 낼게요) · OTHER(상대가 더 내 주세요)
     */
    public record ExtraRequest(
            @NotNull(message = "금액을 입력해 주세요.")
            @Min(value = 0, message = "추가 금액은 0원 이상입니다.")
            @Max(value = 1_000_000, message = "추가 금액은 1,000,000원 이하입니다.") Long amount,
            @NotBlank(message = "누가 낼지 골라 주세요.")
            @Pattern(regexp = "ME|OTHER", message = "payer 는 ME 또는 OTHER 입니다.") String payer) {
    }

    /** POST /api/exchanges/{id}/extra-request 본문(2026-11-25): 받은 사람이 요청하는 추가요금 */
    public record ExtraFeeRequest(
            @NotNull(message = "금액을 입력해 주세요.")
            @Min(value = 1_000, message = "추가요금은 1,000원 이상이에요.")
            @Max(value = 1_000_000, message = "추가요금은 1,000,000원 이하예요.") Long amount) {
    }

    /** 운송장 한 장(교환 카드의 배송 현황). mine = 내가 보낸 물건인지 */
    public record ShipmentView(long senderId, boolean mine, String carrierCode, String carrierName, String invoiceMasked,
                               String status, int level, String levelLabel, String lastDetail, String lastWhere,
                               String lastTime, String deliveredAt) {
        static ShipmentView from(com.sbada.shipping.Shipment s, Long viewerId) {
            return new ShipmentView(s.getSender().getId(), viewerId != null && viewerId.equals(s.getSender().getId()),
                    s.getCarrierCode(), s.getCarrierName(), s.maskedInvoice(), s.getStatus().name(), s.getLevel(),
                    com.sbada.shipping.TrackResult.levelLabel(s.getLevel()), s.getLastDetail(), s.getLastWhere(),
                    s.getLastTime(), s.getDeliveredAt() == null ? null : s.getDeliveredAt().toString());
        }
    }

    /**
     * myRole: 이 요청에서 나의 역할 — REQUESTER(보낸 사람) · RECEIVER(받은 사람)
     * priceDiffPercent: (내놓은 물건 가격 − 원하는 물건 가격) ÷ 원하는 물건 가격 × 100
     */
    public record ExchangeView(long id, String status, String message,
                               UserDtos.PublicUser requester, UserDtos.PublicUser targetOwner,
                               ItemRef.ItemSummary offeredItem, ItemRef.ItemSummary targetItem,
                               int priceDiffPercent, Long chatRoomId, String closeReason,
                               String createdAt, String respondedAt, String myRole,
                               long extraAmount, Long extraPayerId, String extraDirection, String paymentStatus,
                               boolean iReceived, boolean otherReceived,
                               // 2026-11-25 추가요금 요청 · 운송장 · 후기
                               String extraRequestStatus, boolean canRequestExtra, String dealStage, String shipDeadline,
                               String completedAt, List<ShipmentView> shipments, boolean iReviewed, boolean otherReviewed) {
        /**
         * items: 물품 서버에서 받은 물건 요약(번호 → 요약). 없으면(물품 서버가 꺼짐 등) '불러오지 못했어요'로 채운다.
         * 2026-11-25 MSA 2단계: 물건은 메인 DB 에 없으므로 부르는 쪽(ExchangeService)이 배달부로 한 번에 받아 넘긴다.
         */
        public static ExchangeView from(ExchangeRequest e, Long viewerId, java.util.Map<Long, ItemRef> items) {
            ItemRef offeredRef = items.getOrDefault(e.getOfferedItemId(), ItemRef.unknown(e.getOfferedItemId()));
            ItemRef targetRef = items.getOrDefault(e.getTargetItemId(), ItemRef.unknown(e.getTargetItemId()));
            int target = Math.max(1, targetRef.price()); // 0으로 나누기 방지
            // 100.0 으로 곱해 실수 나눗셈을 한 뒤 반올림 → (int) 로 정수 변환
            int diff = targetRef.price() == 0 || offeredRef.price() == 0 ? 0
                    : (int) Math.round((offeredRef.price() - target) * 100.0 / target);
            // 중첩 삼항 연산자: 보는 사람이 없으면 null, 보낸 사람이면 REQUESTER, 받은 사람이면 RECEIVER
            String role = viewerId == null ? null
                    : e.isRequestedBy(viewerId) ? "REQUESTER" : e.isReceivedBy(viewerId) ? "RECEIVER" : null;
            return new ExchangeView(e.getId(), e.getStatus().name(), e.getMessage(),
                    UserDtos.PublicUser.from(e.getRequester()), UserDtos.PublicUser.from(e.getTargetOwner()),
                    offeredRef.summary(), targetRef.summary(),
                    diff, e.getChatRoomId(), e.getCloseReason(), e.getCreatedAt().toString(),
                    e.getRespondedAt() == null ? null : e.getRespondedAt().toString(), role,
                    e.getExtraAmount(), e.payerId(), direction(e, viewerId), e.getPaymentStatus(),
                    received(e, viewerId, true), received(e, viewerId, false),
                    e.getExtraRequestStatus().name(),
                    // 받은 사람 · 대기 중 · 아직 요청 전(또는 거절됨) · 내 물건이 더 쌀 때만 '추가요금 요청' 버튼
                    viewerId != null && e.isReceivedBy(viewerId) && e.getStatus() == ExchangeStatus.REQUESTED
                            && (e.getExtraRequestStatus() == ExtraRequestStatus.NONE || e.getExtraRequestStatus() == ExtraRequestStatus.DECLINED)
                            && !e.isPaymentLocked()
                            && ExchangeService.canRequestExtra(targetRef.price(), offeredRef.price()),
                    e.getDealStage().name(),
                    e.getShipDeadline() == null ? null : e.getShipDeadline().toString(),
                    e.getCompletedAt() == null ? null : e.getCompletedAt().toString(),
                    e.getShipments().stream().map(s -> ShipmentView.from(s, viewerId)).toList(),
                    viewerId != null && e.isParticipant(viewerId) && e.hasReviewed(viewerId),
                    viewerId != null && e.isParticipant(viewerId) && e.hasReviewed(e.otherOf(viewerId)));
        }

        /** 양쪽 수령 확인: mine=true 면 내가, false 면 상대가 '물건 받았어요'를 눌렀는지 */
        private static boolean received(ExchangeRequest e, Long viewerId, boolean mine) {
            if (viewerId == null || (!e.isRequestedBy(viewerId) && !e.isReceivedBy(viewerId))) {
                return false;
            }
            long other = e.isRequestedBy(viewerId) ? e.getTargetOwner().getId() : e.getRequester().getId();
            return e.hasReceived(mine ? viewerId : other);
        }

        /** 보는 사람 기준 추가 금액 방향: I_PAY(내가 냄) · I_RECEIVE(내가 받음) · null(추가 금액 없음) */
        private static String direction(ExchangeRequest e, Long viewerId) {
            Long payer = e.payerId();
            if (payer == null || viewerId == null) {
                return null;
            }
            return payer.equals(viewerId) ? "I_PAY" : "I_RECEIVE";
        }
    }

    /** 목록 응답 {"exchanges": [...]} */
    public record ExchangeList(List<ExchangeView> exchanges) {
    }
}
