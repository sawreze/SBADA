package com.sbada.shipping;

/** 택배사(스마트택배 회사 코드 · 이름). code "TEST" 는 키 없이 흐름을 확인하는 시연용 테스트 택배 */
public record Carrier(String code, String name) {
}
