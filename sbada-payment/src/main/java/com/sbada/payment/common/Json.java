package com.sbada.payment.common;

import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(String json) {
        return MAPPER.readValue(json, Map.class);
    }
}
