package com.sbada.payment.wallet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 시연에서 고를 수 있는 은행 · 결제수단 목록 */
public final class Catalog {

    private Catalog() {
    }

    /** 출금 받을 은행 */
    public static final Map<String, String> BANKS = new LinkedHashMap<>();
    /** 추가 요금 · 충전 결제수단. 카카오뱅크 · 토스뱅크는 '계좌이체(퀵계좌이체)'로 결제된다 */
    public static final Map<String, String> METHODS = new LinkedHashMap<>();

    static {
        BANKS.put("KAKAOBANK", "카카오뱅크");
        BANKS.put("TOSSBANK", "토스뱅크");
        BANKS.put("KB", "KB국민은행");
        BANKS.put("SHINHAN", "신한은행");
        BANKS.put("WOORI", "우리은행");
        BANKS.put("HANA", "하나은행");
        BANKS.put("NH", "NH농협은행");
        BANKS.put("IBK", "IBK기업은행");

        METHODS.put("CARD", "신용·체크카드");
        METHODS.put("KAKAOPAY", "카카오페이");
        METHODS.put("TOSSPAY", "토스페이");
        METHODS.put("TRANSFER", "계좌이체(카카오뱅크·토스뱅크 등)");
    }

    /** 쓰바다 지갑 잔액으로 결제(안전결제 · 추가 요금에서만). 충전 수단 목록(METHODS)에는 넣지 않는다 */
    public static final String WALLET = "WALLET";

    /**
     * 2026-11-25 토스페이먼츠로 실제 승인된 결제(테스트 키). 결제 서버가 토스 승인 · 입금 확인을 한 뒤에만 쓰는 내부 코드라
     * 앱이 /api/payments/escrow 로 직접 보낼 수는 없다(payMethod 가 거절).
     */
    public static final String TOSS_CARD = "TOSS_CARD";
    public static final String TOSS_VA = "TOSS_VA";

    /** 결제수단 코드 → 화면 이름(WALLET 포함). 모르는 코드는 그대로 */
    public static String methodLabel(String code) {
        if (code == null) {
            return null;
        }
        if (TOSS_CARD.equals(code)) {
            return "토스페이먼츠 결제";
        }
        if (TOSS_VA.equals(code)) {
            return "가상계좌(토스페이먼츠)";
        }
        return WALLET.equals(code) ? "쓰바다 지갑" : METHODS.getOrDefault(code, code);
    }

    public record Option(String code, String label) {
    }

    public static List<Option> options(Map<String, String> map) {
        return map.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList();
    }
}
