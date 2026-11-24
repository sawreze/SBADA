package com.sbada.events;

/**
 * 허브(Kafka) 토픽 이름 (2026-11-25 MSA 2단계). 세 서버가 같은 이름을 쓴다.
 *  sbada.item.events      물품 서버 → 메인 : ITEM_CREATED · ITEM_STATUS_CHANGED · ITEM_VIEWED · ITEM_LIKED
 *  sbada.exchange.events  메인 → 물품     : EXCHANGE_REQUESTED · EXCHANGE_ACCEPTED · EXCHANGE_CLOSED
 *  sbada.user.events      메인 → 물품     : USER_UPDATED(닉네임 · 동네)
 *  sbada.payment.events   결제 → 메인     : ESCROW_HELD · ESCROW_RELEASED · WITHDRAWN …
 *  sbada.notifications    누구나 → 메인   : 알림 한 건(받는 사람 · 종류 · 제목 · 본문)
 * 메시지 키 = 물건 · 교환 · 회원 번호 → 같은 대상의 이벤트는 같은 파티션에 들어가 순서가 지켜진다.
 */
public final class Topics {

    public static final String ITEM = "sbada.item.events";
    public static final String EXCHANGE = "sbada.exchange.events";
    public static final String USER = "sbada.user.events";
    public static final String PAYMENT = "sbada.payment.events";
    public static final String NOTIFICATIONS = "sbada.notifications";

    private Topics() {
    }
}
