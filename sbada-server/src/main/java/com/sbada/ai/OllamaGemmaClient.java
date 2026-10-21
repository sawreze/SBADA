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
 * 내 PC의 Ollama 에서 Gemma 를 호출한다(키 필요 없음, 인터넷 없이도 동작).
 * 준비: cmd> ollama pull gemma4:e4b
 * POST http://127.0.0.1:11434/api/generate  {model, prompt, images:[base64], stream:false, format:"json"}
 *
 * ■ 서버 → 로컬 Ollama (외부 API 호출)
 *   GET  http://127.0.0.1:11434/api/tags       켜져 있는지 확인(200 이면 사용 가능)
 *   POST http://127.0.0.1:11434/api/generate
 *     요청: {"model":"gemma4:e4b","prompt":"…","images":["(Base64)"],"stream":false,"format":"json",
 *            "options":{"temperature":0.2}}
 *     응답: {"model":"…","response":"{\"title\":…}","done":true}   ← response 문자열만 꺼낸다
 *
 * [프로그래밍 설명]
 *  - stream:false → 글자가 조금씩 오는 스트리밍 대신 완성된 답을 한 번에 받는다.
 *  - format:"json" → Ollama 가 출력을 JSON 으로 강제한다(해석 실패 감소).
 *  - NO_PROXY: 내 PC(127.0.0.1) 호출이 회사·시스템 프록시를 타지 않게.
 *  - 연결 제한 2초: 꺼져 있을 때 오래 기다리지 않고 바로 대체 추정으로 넘어가게.
 */
@Component
public class OllamaGemmaClient implements GemmaClient {

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final HttpClient http;

    public OllamaGemmaClient(@Value("${sbada.ai.ollama.url}") String baseUrl,
                             @Value("${sbada.ai.ollama.model}") String model,
                             @Value("${sbada.ai.timeout-seconds}") long timeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return !baseUrl.isBlank();
    }

    /** Ollama가 켜져 있는지 2초 안에 확인한다. */
    public boolean isReachable() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String generate(String prompt, byte[] image, String mimeType) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "prompt", prompt,
                "images", List.of(Base64.getEncoder().encodeToString(image)),
                "stream", false,
                "format", "json",
                "options", Map.of("temperature", 0.2));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Ollama HTTP " + response.statusCode());
        }
        Object text = Json.readMap(response.body()).get("response");
        if (text == null) {
            throw new IllegalStateException("Ollama 응답에 response가 없습니다.");
        }
        return text.toString();
    }
}
