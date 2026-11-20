package com.sbada.payment.common;

import java.util.Map;

/** 오류 응답 JSON — 메인 서버와 같은 모양이라 앱은 어느 서비스의 오류든 똑같이 처리한다 */
public record ErrorResponse(String code, String message, Map<String, String> errors) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Map.of());
    }
}
