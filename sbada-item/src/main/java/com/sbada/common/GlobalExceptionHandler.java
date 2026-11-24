package com.sbada.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 예외를 일정한 오류 JSON으로 바꾼다. 내부 SQL·스택 추적·비밀값은 응답에 넣지 않는다(7장 4.5).
 *
 * [프로그래밍 설명 — 모든 API 의 '오류 응답'은 여기서 만들어진다]
 *  - @RestControllerAdvice: 모든 @RestController 에 공통으로 적용되는 '예외 처리 담당' 빈.
 *    Controller(또는 그 안에서 부른 Service)에서 예외가 밖으로 나오면, Spring MVC 가 예외 타입에 맞는
 *    @ExceptionHandler 메서드를 찾아 대신 응답을 만든다. (Controller 에 try-catch 를 쓰지 않는 이유)
 *  - ResponseEntity<T>: 응답 본문(T)과 HTTP 상태 코드·헤더를 함께 정하는 객체.
 *    ResponseEntity.status(404).body(…) → 상태 404 + 본문 JSON.
 *  - 오류 응답 예시(HTTP 404):
 *      {"code":"ITEM_NOT_FOUND","message":"물건을 찾을 수 없습니다.","errors":{}}
 *  - 앱(Flutter)의 api_exception.dart 가 이 모양을 읽어 code 로 분기하고 message 를 화면에 보여 준다.
 *
 *  참고: 401(로그인 필요)·403(권한 없음) 중 'Spring Security 필터 단계'에서 막힌 요청은 Controller 까지 오지 않으므로
 *        여기가 아니라 SecurityConfig 의 authenticationEntryPoint/accessDeniedHandler 가 같은 모양으로 응답한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // SLF4J 로거: 서버 콘솔/로그 파일(logs/2-server.log)에 기록한다. 응답에는 나가지 않는다.
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * ① 우리가 직접 던진 업무 오류(ApiException) → 그 안의 status·code·message 를 그대로 응답.
     *    예: 남의 물건 삭제 → 403 {"code":"NOT_ITEM_OWNER", …}
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage(), e.getErrors()));
    }

    /**
     * ② @Valid 검증 실패 → 400 VALIDATION_FAILED.
     *    Controller 파라미터에 @Valid @RequestBody 가 붙어 있으면, JSON → DTO 변환 직후
     *    DTO 필드의 @NotBlank·@Size·@Pattern 등을 검사하고, 어기면 MethodArgumentNotValidException 이 난다.
     *    getFieldErrors() 로 '어느 필드가 왜' 틀렸는지 모아 errors 에 담는다.
     *    응답 예: {"code":"VALIDATION_FAILED","message":"입력값을 확인해 주세요.",
     *             "errors":{"username":"아이디는 영문 소문자·숫자·밑줄 4~20자입니다."}}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>(); // LinkedHashMap: 넣은 순서를 유지(응답에서 필드 순서가 일정)
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()); // 한 필드에 오류가 여러 개면 첫 번째만
        }
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "입력값을 확인해 주세요.", errors));
    }

    // Jackson 오류 메시지에서 모르는 필드 이름을 뽑아내는 정규식: Unrecognized field "price" → price
    private static final Pattern UNKNOWN_FIELD = Pattern.compile("Unrecognized (?:property|field) \"([^\"]+)\"");

    /**
     * ③ 요청 본문(JSON)을 DTO 로 바꾸지 못함 → 400.
     *    - JSON 문법이 틀림 → INVALID_JSON
     *    - DTO 에 없는 필드를 보냄(예: 등록할 때 "price") → UNKNOWN_FIELD
     *      application.properties 의 spring.jackson…fail-on-unknown-properties=true 설정 때문에
     *      모르는 필드가 오면 Jackson 이 UnrecognizedPropertyException 을 낸다.
     *      → 가격·상태·등록자처럼 '서버가 정하는 값'을 클라이언트가 조작하지 못하게 막는 장치(7장).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause(); // 감싸진 예외들 중 가장 안쪽 원인
        if (cause != null && cause.getClass().getSimpleName().equals("UnrecognizedPropertyException")) {
            // 가격·상태·등록자처럼 서버가 정하는 값을 본문에 넣으면 거절한다
            Matcher m = UNKNOWN_FIELD.matcher(String.valueOf(cause.getMessage()));
            String field = m.find() ? m.group(1) : "unknown";
            return ResponseEntity.badRequest().body(new ErrorResponse("UNKNOWN_FIELD",
                    "보낼 수 없는 항목이 있습니다: " + field,
                    Map.of(field, "서버가 정하는 값이거나 없는 항목입니다.")));
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_JSON", "요청 본문(JSON)의 형식이 올바르지 않습니다."));
    }

    /**
     * ④ 주소의 값이 틀림 → 400 INVALID_PARAMETER.
     *    - /api/items/abc 처럼 @PathVariable long id 에 숫자가 아닌 값 → MethodArgumentTypeMismatchException
     *    - 필수 @RequestParam 이 빠짐 → MissingServletRequestParameterException
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ErrorResponse> badParam(Exception e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_PARAMETER", "요청 주소의 값이 올바르지 않습니다."));
    }

    /** ⑤ 사진이 spring.servlet.multipart.max-file-size(5MB)를 넘음 → 413 Payload Too Large */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413)
                .body(ErrorResponse.of("FILE_TOO_LARGE", "사진은 5MB 이하만 올릴 수 있습니다."));
    }

    /** ⑥ 사진 업로드를 multipart/form-data 형식이 아닌 방식으로 보냄 → 400 */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ErrorResponse> multipart(MultipartException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_MULTIPART", "file 항목에 사진을 넣어 multipart/form-data로 보내 주세요."));
    }

    /**
     * ⑦ 낙관적 잠금 충돌 → 409 CONCURRENT_UPDATE.
     *    Item 엔티티의 @Version 필드는 저장할 때마다 1씩 오른다.
     *    두 요청이 같은 버전을 읽고 동시에 저장하면, 늦은 쪽은 'WHERE version = 읽은 값' 조건에 맞는 행이 없어
     *    이 예외가 난다 → 같은 물건으로 두 교환이 동시에 성사되는 일을 막는다(ExchangeService.accept 참고).
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> optimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(409)
                .body(ErrorResponse.of("CONCURRENT_UPDATE", "다른 요청이 먼저 처리되었습니다. 새로고침 후 다시 시도해 주세요."));
    }

    /** ⑧ 주소는 있는데 방식이 틀림(예: GET 만 있는 곳에 DELETE) → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405)
                .body(ErrorResponse.of("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다."));
    }

    /** ⑨ 없는 주소 → 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(ErrorResponse.of("NOT_FOUND", "요청한 주소를 찾을 수 없습니다."));
    }

    /**
     * ⑩ 위에서 잡지 못한 모든 예외 → 500.
     *    원인(스택 추적)은 서버 로그에만 남기고, 응답에는 일반 문구만 보낸다(내부 정보 노출 방지).
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unknown(Exception e) {
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(500)
                .body(ErrorResponse.of("INTERNAL_ERROR", "서버에서 오류가 발생했습니다."));
    }
}
