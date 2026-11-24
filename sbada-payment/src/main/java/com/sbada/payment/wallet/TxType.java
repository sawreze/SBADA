package com.sbada.payment.wallet;

/** 거래 종류(DB 에는 이름 문자열로 저장) */
public enum TxType {
    DEMO_CHARGE("시연 기본 잔액"),
    CHARGE("충전"),
    WITHDRAW("출금"),
    PAY("추가 요금 결제"),
    RECEIVE("추가 요금 받음"),
    // 2026-11-25 안전결제(에스크로) · 샌드박스
    ESCROW_HOLD("안전결제 결제"),
    ESCROW_RELEASE("안전결제 정산"),
    ESCROW_REFUND("안전결제 환불"),
    SANDBOX_RESET("테스트 잔액 초기화");

    private final String label;

    TxType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String labelOf(String name) {
        try {
            return valueOf(name).label;
        } catch (IllegalArgumentException e) {
            return name;
        }
    }
}
