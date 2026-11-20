package com.sbada.payment.wallet;

/** 거래 종류(DB 에는 이름 문자열로 저장) */
public enum TxType {
    DEMO_CHARGE("시연 기본 잔액"),
    CHARGE("충전"),
    WITHDRAW("출금"),
    PAY("추가 요금 결제"),
    RECEIVE("추가 요금 받음");

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
