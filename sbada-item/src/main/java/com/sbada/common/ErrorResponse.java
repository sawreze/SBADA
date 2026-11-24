package com.sbada.common;

import java.util.Map;

/**
 * 모든 오류 응답의 모양: {"code": "...", "message": "...", "errors": {"필드": "안내"}}
 *
 * [프로그래밍 설명]
 *  - record(Java 16+): 값을 담기만 하는 불변 클래스를 한 줄로 만든다. 컴파일러가 private final 필드,
 *    생성자, code()/message()/errors() 접근자, equals/hashCode/toString 을 자동으로 만들어 준다.
 *  - Spring(Jackson)이 record 를 JSON 으로 바꿀 때 접근자 이름이 그대로 JSON 키가 된다.
 */
public record ErrorResponse(String code, String message, Map<String, String> errors) {

    /** 칸별 안내(errors)가 없는 오류용 짧은 생성 메서드 */
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Map.of());
    }
}
