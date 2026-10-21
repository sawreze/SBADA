package com.sbada.common;

import java.util.Map;

/**
 * 업무 규칙 위반을 HTTP 상태와 code로 표현한다(7장 4.5의 {code, message, errors}).
 * 400 입력 오류 · 401 인증 · 403 권한 · 404 없음 · 409 상태 충돌
 *
 * [프로그래밍 설명]
 *  - RuntimeException(언체크 예외)을 상속했으므로 메서드 선언에 throws 를 쓰지 않아도 어디서든 던질 수 있다.
 *  - Service 에서 throw ApiException.notFound(...) 하면 → Controller 를 거쳐 밖으로 전파되고
 *    → GlobalExceptionHandler 의 @ExceptionHandler(ApiException.class) 가 잡아 {code, message, errors} JSON 으로 바꾼다.
 *    그래서 Controller 마다 try-catch 를 쓰지 않아도 오류 응답 모양이 항상 같다.
 *  - 또 RuntimeException 은 @Transactional 안에서 던지면 자동으로 ROLLBACK 된다(중간까지 한 DB 변경이 취소됨).
 *  - static 팩토리 메서드(badRequest, notFound …): new ApiException(404, …) 대신 이름으로 의도를 드러낸다.
 */
public class ApiException extends RuntimeException {

    private final int status;   // HTTP 상태 코드(400, 403 …)
    private final String code;  // 앱이 분기할 때 쓰는 고정 문자열(예: ITEM_NOT_FOUND)
    private final Map<String, String> errors; // 입력 칸별 안내(없으면 빈 Map)

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of()); // 생성자 체이닝: 아래 생성자를 다시 부른다
    }

    public ApiException(int status, String code, String message, Map<String, String> errors) {
        super(message); // 부모(RuntimeException)에 메시지를 넘기면 getMessage() 로 꺼낼 수 있다
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getErrors() {
        return errors;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }
}
