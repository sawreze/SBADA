package com.sbada.shipping;

/**
 * 택배 조회 결과 한 번 (스마트택배 · 시연용 테스트 택배 공통).
 *  found=false 면 error 에 이유(운송장 번호가 틀림 · 아직 접수 전 등).
 *  level: 1 준비 · 2 집화 · 3 배송 중 · 4 지점 도착 · 5 배송 출발 · 6 배송 완료
 */
public record TrackResult(boolean found, int level, boolean delivered, String detail, String where, String time,
                          String error) {

    public static TrackResult notFound(String error) {
        return new TrackResult(false, 0, false, null, null, null, error);
    }

    /** 단계 이름(화면 · 채팅용) */
    public static String levelLabel(int level) {
        return switch (level) {
            case 1 -> "배송 준비 중";
            case 2 -> "택배사 접수(집화)";
            case 3 -> "배송 중";
            case 4 -> "배송 지점 도착";
            case 5 -> "배송 출발";
            case 6 -> "배송 완료";
            default -> "확인 중";
        };
    }
}
