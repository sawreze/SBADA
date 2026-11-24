package com.sbada.common;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot 4는 Jackson 3(tools.jackson 패키지)을 쓴다(7장 1.5 오류표 참고).
 * WebSocket 메시지·외부 AI 응답처럼 Controller 밖에서 JSON을 다룰 때 이 도우미를 쓴다.
 *
 * [프로그래밍 설명]
 *  - Controller 에서는 Spring 이 자동으로 JSON ⇄ 객체 변환을 해 준다(@RequestBody / 반환값).
 *    하지만 WebSocket 문자열 메시지, Gemma API 응답 같은 곳은 직접 변환해야 해서 이 클래스를 쓴다.
 *  - 직렬화(serialize)   : 객체 → JSON 문자열  (write)
 *  - 역직렬화(deserialize): JSON 문자열 → 객체/Map (read, readMap)
 *  - JsonMapper 는 만들기 비싸고 스레드에 안전하므로 static final 로 하나만 만들어 재사용한다.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** 객체(Map, record 등) → JSON 문자열 */
    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** JSON 문자열 → Map(키: 문자열, 값: 숫자/문자열/List/Map …). 구조를 미리 모르는 응답에 쓴다. */
    @SuppressWarnings("unchecked") // Map.class 로는 제네릭 타입(Map<String,Object>)을 확인할 수 없어 나는 경고를 끈다
    public static Map<String, Object> readMap(String json) {
        return MAPPER.readValue(json, Map.class);
    }

    /** JSON 문자열 → 지정한 클래스의 객체. 구조를 아는 메시지에 쓴다. */
    public static <T> T read(String json, Class<T> type) { // <T>: 제네릭 메서드. 넘긴 클래스 타입 그대로 돌려준다
        return MAPPER.readValue(json, type);
    }
}
