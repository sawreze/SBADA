package com.sbada.ai;

/**
 * Gemma 모델 호출 방법(클라우드 Gemini API 또는 로컬 Ollama)을 같은 모양으로 감싼다.
 *
 * [프로그래밍 설명 — 인터페이스와 다형성]
 *  - 인터페이스: '무엇을 할 수 있는지'(메서드 이름·모양)만 정하고, '어떻게'는 구현 클래스가 정한다.
 *      GoogleGemmaClient : Google AI Studio(Gemini API)로 HTTPS 호출
 *      OllamaGemmaClient : 내 PC 의 Ollama 서버(127.0.0.1:11434)로 호출
 *  - AiPriceService 는 GemmaClient 타입으로만 다루므로, 어느 쪽이든 같은 코드(client.generate(…))로 부를 수 있다.
 *    → 새 AI 제공자를 추가해도 분석 로직은 바꾸지 않아도 된다(전략 패턴).
 *  - generate 가 throws Exception 인 이유: 네트워크 오류·시간 초과·HTTP 오류 등 무엇이든 실패할 수 있고,
 *    AiPriceService 가 모두 잡아서 '다음 방법(fallback)'으로 넘어가기 때문이다.
 */
public interface GemmaClient {

    /** 제공자 이름: google · ollama */
    String name();

    String model();

    /** 호출에 필요한 설정(키·주소)이 있는가 */
    boolean isConfigured();

    /** 사진과 지시문을 보내고 모델이 쓴 글자(JSON 기대)를 그대로 돌려준다. */
    String generate(String prompt, byte[] image, String mimeType) throws Exception;
}
