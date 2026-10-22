package com.sbada.notification;

/**
 * 알림 종류. API 응답의 "type" 값으로 그대로 나가며, 앱은 이 값으로 아이콘·이동할 화면을 정한다.
 */
public enum NotificationType {
    /** 누군가 내 물건에 '바로 바꾸기'를 눌렀다 → 수락/거절 필요 */
    EXCHANGE_REQUESTED,
    /** 내가 보낸 교환 요청이 수락되었다(교환 성사) */
    EXCHANGE_ACCEPTED,
    /** 내가 보낸 교환 요청이 거절되었다 */
    EXCHANGE_REJECTED,
    /** 상대가 요청을 취소했거나 물건이 다른 사람과 교환되어 자동 취소되었다 */
    EXCHANGE_CANCELED,
    /** 내 물건과 비슷한 가격대의 새 물건이 올라왔다 */
    MATCH_SUGGESTED,
    /** 새 채팅(알림 목록에는 저장하지 않고 실시간·푸시로만 보낸다) */
    CHAT_MESSAGE,
    /** 내가 올린 물건이 콘텐츠 검사(욕설 · 선정적 · 혐오)에 걸려 보류되었다(V7) */
    ITEM_HELD,
    /** 누군가 내 물건을 찜했다(V14) */
    ITEM_LIKED;

    /** 계정정보 > 알림 설정의 어느 스위치에 속하는지: like · chat · exchange (그 밖의 알림은 항상 보냄 → null) */
    public String settingKey() {
        return switch (this) {
            case ITEM_LIKED -> "like";
            case CHAT_MESSAGE -> "chat";
            case EXCHANGE_REQUESTED, EXCHANGE_ACCEPTED, EXCHANGE_REJECTED, EXCHANGE_CANCELED -> "exchange";
            default -> null;
        };
    }
}
