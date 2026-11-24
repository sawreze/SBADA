package com.sbada.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.common.Json;

/**
 * Google AI Studio(Gemini API)에서 Gemma 4 를 호출한다.
 * POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * API 키는 헤더(x-goog-api-key)로만 보내고, 로그·응답·Git에 남기지 않는다.
 *
 * ■ 서버 → 구글 (외부 API 호출)
 *   POST https://generativelanguage.googleapis.com/v1beta/models/gemma-4-26b-a4b-it:generateContent
 *   헤더: Content-Type: application/json, x-goog-api-key: {키}
 *   요청 본문:
 *     {"contents":[{"role":"user","parts":[
 *         {"text":"너는 한국 중고거래 시세에 밝은 … JSON 하나만 출력…"},
 *         {"inlineData":{"mimeType":"image/jpeg","data":"(사진 Base64)"}}]}],
 *      "generationConfig":{"temperature":0.2,"maxOutputTokens":8192}}
 *   응답 본문(필요한 부분):
 *     {"candidates":[{"content":{"parts":[{"text":"…생각…","thought":true},{"text":"{\"title\":…}"}]}}]}
 *
 * [프로그래밍 설명]
 *  - java.net.http.HttpClient(Java 11+): 별도 라이브러리 없이 HTTP 요청을 보내는 표준 클래스.
 *    connectTimeout 10초(연결), timeout(설정값, 응답 대기)을 따로 둔다.
 *  - 사진은 바이너리라 JSON 에 넣을 수 없어서 Base64 문자열로 바꿔 inlineData 에 담는다.
 *  - temperature 0.2: 답이 매번 크게 달라지지 않게(가격 추정은 창의성보다 일관성).
 *  - maxOutputTokens 8192: Gemma 4 는 답 전에 '생각(thought)'을 길게 쓰므로, 작으면 JSON 이 잘려 나온다(실제로 겪은 오류).
 *  - 2xx 가 아니면 예외 → AiPriceService 가 대체 경로로 넘어간다(키 오류 400/403, 서버 오류 500 등).
 *  - @Value("${sbada.ai.google.api-key:}"): 콜론 뒤가 기본값(빈 문자열) → 키가 없어도 서버는 켜진다(isConfigured=false).
 */
@Component
public class GoogleGemmaClient implements GemmaClient {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    public GoogleGemmaClient(@Value("${sbada.ai.google.api-key:}") String apiKey,
                             @Value("${sbada.ai.google.model}") String model,
                             @Value("${sbada.ai.timeout-seconds}") long timeoutSeconds) {
        this.apiKey = cleanKey(apiKey);
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** 설정 파일에 키를 "따옴표"로 감싸 넣어도 동작하도록 앞뒤 공백·따옴표를 지운다. */
    static String cleanKey(String raw) {
        if (raw == null) {
            return "";
        }
        String k = raw.trim();
        while (k.length() >= 2 && ((k.startsWith("\"") && k.endsWith("\"")) || (k.startsWith("'") && k.endsWith("'")))) {
            k = k.substring(1, k.length() - 1).trim();
        }
        return k;
    }

    @Override
    public String name() {
        return "google";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public String generate(String prompt, byte[] image, String mimeType) throws Exception {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(
                                Map.of("text", prompt),
                                Map.of("inlineData", Map.of(
                                        "mimeType", mimeType,
                                        "data", Base64.getEncoder().encodeToString(image)))))),
                "generationConfig", Map.of("temperature", 0.2, "maxOutputTokens", 8192));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + model + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Gemini API HTTP " + response.statusCode());
        }
        return extractText(Json.readMap(response.body()));
    }

    /**
     * candidates[0].content.parts[*].text 를 이어 붙인다. 생각(thought) 부분은 뺀다.
     * Json.readMap 결과는 Map/List 가 섞인 Object 라서 형변환이 필요하다 → @SuppressWarnings("unchecked") 로 경고를 끈다.
     * instanceof List<?> list: 타입 확인과 변수 선언을 한 번에(패턴 매칭, Java 16+).
     */
    @SuppressWarnings("unchecked")
    static String extractText(Map<String, Object> root) {
        Object candidates = root.get("candidates");
        if (!(candidates instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalStateException("Gemini 응답에 candidates가 없습니다.");
        }
        Map<String, Object> first = (Map<String, Object>) list.get(0);
        Map<String, Object> content = (Map<String, Object>) first.get("content");
        if (content == null) {
            throw new IllegalStateException("Gemini 응답에 content가 없습니다.");
        }
        StringBuilder sb = new StringBuilder();
        for (Object p : (List<Object>) content.getOrDefault("parts", List.of())) {
            Map<String, Object> part = (Map<String, Object>) p;
            if (Boolean.TRUE.equals(part.get("thought"))) {
                continue;
            }
            Object text = part.get("text");
            if (text != null) {
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
