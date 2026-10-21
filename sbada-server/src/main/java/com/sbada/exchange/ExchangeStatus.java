package com.sbada.exchange;

/**
 * 교환 요청 상태(docs/state-rules.md).
 *
 *   REQUESTED ──(받은 사람 수락)──▶ ACCEPTED   : 두 물건 모두 TRADED
 *   REQUESTED ──(받은 사람 거절)──▶ REJECTED
 *   REQUESTED ──(보낸 사람 취소 / 물건이 다른 교환으로 거래됨·삭제됨)──▶ CANCELED
 *   끝난 상태(ACCEPTED·REJECTED·CANCELED)에서 다시 바꾸려 하면 409 INVALID_STATUS
 *
 * [프로그래밍 설명]
 *  - 상태 기계(state machine): 가능한 상태와 '어느 상태에서 어느 상태로 갈 수 있는지'를 정해 둔 것.
 *    전이 규칙은 ExchangeRequest.close() 한 곳에서 검사한다.
 */
public enum ExchangeStatus {
    REQUESTED, ACCEPTED, REJECTED, CANCELED
}
