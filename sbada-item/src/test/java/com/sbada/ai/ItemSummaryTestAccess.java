package com.sbada.ai;

/** 테스트에서 ItemSummaryService 의 package-private 정리 함수를 부르기 위한 다리 */
public final class ItemSummaryTestAccess {

    private ItemSummaryTestAccess() {
    }

    public static String parse(String gemmaText) throws Exception {
        return ItemSummaryService.clean(ItemSummaryService.parse(gemmaText));
    }
}
