package com.sbada.shipping;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.sbada.common.ApiException;
import com.sbada.common.Json;

/**
 * 택배 조회 (2026-11-25 사용자 요청). 운송장이 진짜로 택배사에 접수됐는지 확인하고, 배송 현황을 받아 온다.
 *  - 스마트택배(SweetTracker) API: info.sweettracker.co.kr/api/v1/trackingInfo?t_key&t_code&t_invoice
 *    키는 Git 에서 빠지는 application-local.properties 의 sbada.tracking.sweettracker-key (결제_택배_키_설정.command).
 *    무료 등급은 월 1,000건이라 자주 묻지 않는다(ShippingService 가 간격 조절).
 *  - 시연용 '테스트 택배'(code TEST): 키 없이 흐름을 확인. 10~14자리 숫자면 접수된 것으로 보고,
 *    등록 뒤 1분마다 단계가 올라가 약 4분 뒤 배송 완료가 된다(실제 택배사에 묻지 않음).
 */
@Service
public class TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingService.class);
    public static final String TEST_CARRIER = "TEST";
    private static final String BASE = "https://info.sweettracker.co.kr/api/v1";

    /** 키가 없거나 목록을 못 받을 때 쓰는 주요 택배사(스마트택배 회사 코드) */
    static final List<Carrier> DEFAULT_CARRIERS = List.of(
            new Carrier("04", "CJ대한통운"), new Carrier("01", "우체국택배"), new Carrier("05", "한진택배"),
            new Carrier("08", "롯데택배"), new Carrier("06", "로젠택배"), new Carrier("46", "CU 편의점택배"),
            new Carrier("24", "GS Postbox 편의점택배"), new Carrier("23", "경동택배"), new Carrier("22", "대신택배"),
            new Carrier("11", "일양로지스"));

    private final String key;
    private final boolean demoCarrier;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile List<Carrier> cachedCarriers;

    public TrackingService(@Value("${sbada.tracking.sweettracker-key:}") String key,
                           @Value("${sbada.tracking.demo-carrier:true}") boolean demoCarrier) {
        this.key = key == null ? "" : key.trim();
        this.demoCarrier = demoCarrier;
    }

    public boolean hasRealApi() {
        return !key.isEmpty();
    }

    /** 앱의 택배사 고르기 목록. 키가 있으면 스마트택배 목록(한 번 받아 기억), 시연용 테스트 택배를 맨 앞에 */
    public List<Carrier> carriers() {
        List<Carrier> list = new ArrayList<>();
        if (demoCarrier) {
            list.add(new Carrier(TEST_CARRIER, "테스트 택배(시연용)"));
        }
        list.addAll(hasRealApi() ? realCarriers() : DEFAULT_CARRIERS);
        return list;
    }

    public String carrierName(String code) {
        return carriers().stream().filter(c -> c.code().equals(code)).map(Carrier::name).findFirst()
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_CARRIER", "택배사를 다시 골라 주세요."));
    }

    /**
     * 운송장 조회. since = 처음 등록한 시각(테스트 택배의 단계 계산용).
     * 실제 API 를 못 쓰면(키 없음 · 연결 실패) 예외 503 — 운송장이 틀렸다는 뜻이 아니므로 사용자에게 그대로 알린다.
     */
    public TrackResult track(String carrierCode, String invoice, Instant since) {
        if (TEST_CARRIER.equals(carrierCode)) {
            if (!demoCarrier) {
                throw ApiException.badRequest("UNKNOWN_CARRIER", "택배사를 다시 골라 주세요.");
            }
            return demo(invoice, since);
        }
        if (!hasRealApi()) {
            throw new ApiException(503, "TRACKING_UNAVAILABLE",
                    "택배 조회 키가 아직 없어요. 결제_택배_키_설정.command 로 스마트택배 키를 넣거나 '테스트 택배(시연용)'를 골라 주세요.");
        }
        return sweetTracker(carrierCode, invoice);
    }

    // ───────────── 시연용 테스트 택배 ─────────────

    static TrackResult demo(String invoice, Instant since) {
        if (invoice == null || !invoice.matches("\\d{10,14}")) {
            return TrackResult.notFound("운송장 번호를 다시 입력해 주세요. (테스트 택배는 숫자 10~14자리)");
        }
        long minutes = since == null ? 0 : Math.max(0, Duration.between(since, Instant.now()).toMinutes());
        int level = (int) Math.min(6, 2 + minutes); // 등록 직후 2(집화) → 1분마다 한 단계 → 4분 뒤 6(완료)
        String[] where = {"", "", "보내는 곳 편의점", "서울 허브", "동네 대리점", "배달 기사", "받는 곳"};
        return new TrackResult(true, level, level >= 6, TrackResult.levelLabel(level), where[level],
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).withNano(0).toString().replace('T', ' '), null);
    }

    // ───────────── 스마트택배 ─────────────

    private TrackResult sweetTracker(String code, String invoice) {
        Map<String, Object> body = get("/trackingInfo?t_key=" + enc(key) + "&t_code=" + enc(code) + "&t_invoice=" + enc(invoice));
        // 오류 응답: {"status":false,"code":"104","msg":"…운송장…"}
        if (Boolean.FALSE.equals(body.get("status")) || (body.get("code") != null && body.get("msg") != null && body.get("level") == null)) {
            String msg = String.valueOf(body.getOrDefault("msg", ""));
            if (msg.contains("키") || msg.toLowerCase().contains("key")) {
                log.warn("스마트택배 키 오류: {}", msg);
                throw new ApiException(503, "TRACKING_KEY_INVALID", "택배 조회 키가 올바르지 않아요. 결제_택배_키_설정.command 로 다시 넣어 주세요.");
            }
            return TrackResult.notFound("운송장 번호를 다시 입력해 주세요. (" + (msg.isBlank() ? "조회되지 않는 번호" : msg) + ")");
        }
        List<?> details = body.get("trackingDetails") instanceof List<?> l ? l : List.of();
        if (details.isEmpty()) {
            return TrackResult.notFound("아직 택배사에 접수되지 않은 번호예요. 접수 후 운송장 번호를 다시 입력해 주세요.");
        }
        Map<?, ?> last = details.get(details.size() - 1) instanceof Map<?, ?> m ? m : Map.of();
        int level = toInt(body.get("level"));
        if (level == 0) {
            level = toInt(last.get("level"));
        }
        boolean done = Boolean.TRUE.equals(body.get("complete")) || "Y".equals(body.get("completeYN")) || level >= 6;
        String kind = str(last.get("kind"));
        return new TrackResult(true, Math.max(1, Math.min(6, level)), done,
                kind == null ? TrackResult.levelLabel(level) : kind, str(last.get("where")), str(last.get("timeString")), null);
    }

    private List<Carrier> realCarriers() {
        List<Carrier> c = cachedCarriers;
        if (c != null) {
            return c;
        }
        try {
            Map<String, Object> body = get("/companylist?t_key=" + enc(key));
            List<Carrier> list = new ArrayList<>();
            if (body.get("Company") instanceof List<?> companies) {
                for (Object o : companies) {
                    if (o instanceof Map<?, ?> m && !"true".equals(String.valueOf(m.get("International")))) {
                        list.add(new Carrier(String.valueOf(m.get("Code")), String.valueOf(m.get("Name"))));
                    }
                }
            }
            if (!list.isEmpty()) {
                cachedCarriers = List.copyOf(list);
                return cachedCarriers;
            }
        } catch (ApiException e) {
            log.warn("스마트택배 택배사 목록을 못 받아 기본 목록을 써요: {}", e.getMessage());
        }
        return DEFAULT_CARRIERS;
    }

    private Map<String, Object> get(String pathAndQuery) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + pathAndQuery))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new ApiException(503, "TRACKING_UNAVAILABLE", "택배 조회 서버 응답이 올바르지 않아요(HTTP " + res.statusCode() + ").");
            }
            return Json.readMap(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // 키가 들어 있는 주소는 로그에 남기지 않는다
            log.warn("스마트택배 호출 실패: {}", e.getClass().getSimpleName());
            throw new ApiException(503, "TRACKING_UNAVAILABLE", "택배 조회 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
