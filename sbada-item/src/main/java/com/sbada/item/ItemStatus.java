package com.sbada.item;

/**
 * 물건 상태(8장 상태 규칙).
 * AVAILABLE ─(교환 수락)→ TRADED
 * AVAILABLE ─(삭제)→ HIDDEN   (교환·채팅 기록이 물건을 가리키므로 행을 지우지 않고 숨긴다)
 *
 * [프로그래밍 설명]
 *  - '소프트 삭제(soft delete)': DELETE 문으로 행을 지우는 대신 상태 값만 바꿔 숨긴다.
 *    다른 테이블(교환 요청·채팅방)이 외래 키로 이 물건을 가리키고 있어, 진짜로 지우면 기록이 깨지기 때문이다.
 *  - 목록·상세 조회는 HIDDEN 을 제외하므로 사용자에게는 지워진 것처럼 보인다.
 */
public enum ItemStatus {
    AVAILABLE, TRADED, HIDDEN,
    /** 보류: 콘텐츠 검사(욕설 · 선정적 · 혐오 사진)에 걸린 물건. 목록 · 검색 · 추천에서 빠지고 주인만 볼 수 있다(V7). */
    HELD
}
