package com.sbada.exchange;

/**
 * 추가요금 요청 단계 (2026-11-25 사용자 요청).
 *   NONE ──받은 사람 '추가요금 요청'──▶ REQUESTED ──보낸 사람 수락──▶ AGREED ──결제 확인──▶ (교환 성사)
 *                                          └──보낸 사람 거절──▶ DECLINED ──▶ 받은 사람이 다시 선택(바로 수락 · 다시 요청 · 거절)
 * 요청은 '물건 가격이 더 낮은 쪽(받은 사람)'이 '더 높은 쪽(보낸 사람)'에게만 할 수 있다(사용자 결정).
 */
public enum ExtraRequestStatus {
    NONE,
    REQUESTED,
    AGREED,
    DECLINED
}
