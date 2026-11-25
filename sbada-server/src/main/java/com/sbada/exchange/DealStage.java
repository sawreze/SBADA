package com.sbada.exchange;

/**
 * 교환 성사 뒤 단계 (2026-11-25 사용자 요청).
 *   NONE ──성사──▶ SHIPPING(두 사람이 7일 안에 운송장 입력 → 배송 현황 추적)
 *                    ├──두 물건 모두 배송 완료──▶ COMPLETED(보관 중인 추가요금 정산 · 거래 후기 쓰기)
 *                    └──7일 안에 둘 다 입력하지 않음──▶ EXPIRED(교환 취소 · 전액 환불 · 물건 다시 교환 가능)
 */
public enum DealStage {
    NONE,
    SHIPPING,
    COMPLETED,
    EXPIRED
}
