package com.sbada.exchange;

/**
 * 교환 추가 금액의 안전결제 상태 (2026-11-25). 돈은 결제 서비스(sbada-payment)가 갖고, 메인 서버는 상태만 안다.
 *
 *   NONE ──결제(ESCROW_HELD 이벤트)──▶ PAID_HELD ──거래 완료 확정 · 자동 확정──▶ RELEASED(받는 사람 지갑으로)
 *                                          └──거절 · 취소 · 받는 사람이 돌려줌──▶ REFUND_PENDING ──▶ REFUNDED
 */
public enum PaymentStatus {
    /** 아직 안 냄(추가 금액이 없거나 결제 전) */
    NONE,
    /** 결제되어 결제 서비스가 보관 중 */
    PAID_HELD,
    /** 교환을 마쳐 받는 사람 지갑으로 정산됨 */
    RELEASED,
    /** 교환이 깨져 환불을 요청함(결제 서비스 응답 · 이벤트를 기다리는 중) */
    REFUND_PENDING,
    /** 낸 사람에게 환불됨 */
    REFUNDED
}
