package com.sbada.legacy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.Json;
import com.sbada.grpc.InternalToken;

/**
 * 처음 한 번: 메인 서버 DB 에 있던 물건 · 사진 · 찜 · AI 분석 · 판매자를 물품 DB 로 옮긴다 (MSA 2단계 데이터 이전)
 *
 *  - 물품 DB 가 비어 있을 때만(물건 · 사진 0개) 메인 서버의 GET /internal/legacy/items-export 를 부른다(내부 토큰).
 *  - 번호(id)를 그대로 옮긴다 → 교환 요청 · 채팅방이 가리키는 물건 번호가 그대로 맞는다. 옮긴 뒤 자동 번호를 그 다음부터.
 *  - 메인 서버가 아직 안 켜졌으면 30초 뒤 다시. 한 번 옮기면 다시 하지 않는다(DB 가 비어 있지 않으므로).
 *  - 메인 서버 쪽 옛 표는 지우지 않는다(되돌릴 수 있게). 메인 서버는 더 이상 그 표를 쓰지 않는다.
 */
@Component
public class LegacyImporter {

    private static final Logger log = LoggerFactory.getLogger(LegacyImporter.class);
    /** 옮기는 순서(외래 키 때문에 물건 → 사진 → 나머지) */
    private static final List<String> TABLES = List.of("sellers", "items", "item_wish_categories", "item_images",
            "ai_analyses", "item_likes");
    private static final Set<String> TIMES = Set.of("created_at", "updated_at");
    private static final Set<String> IDENTITY = Set.of("items", "item_images", "ai_analyses", "item_likes");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final InternalToken token;
    private final String coreUrl;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile boolean done;

    public LegacyImporter(JdbcTemplate jdbc, TransactionTemplate tx, InternalToken token,
                          @Value("${sbada.core.url:http://127.0.0.1:8097}") String coreUrl,
                          @Value("${sbada.item.import.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.token = token;
        this.coreUrl = coreUrl.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 2000, fixedDelay = 30000)
    public void importOnce() {
        if (!enabled || done) {
            return;
        }
        Integer rows = jdbc.queryForObject("select (select count(*) from items) + (select count(*) from item_images)",
                Integer.class);
        if (rows != null && rows > 0) {
            done = true;
            return;
        }
        String body;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(coreUrl + "/internal/legacy/items-export"))
                    .timeout(Duration.ofSeconds(30)).header(InternalToken.HEADER, token.value()).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                log.info("옛 물건 데이터 가져오기 보류(메인 서버 응답 {}) — 30초 뒤 다시", res.statusCode());
                return;
            }
            body = res.body();
        } catch (Exception e) {
            log.info("옛 물건 데이터 가져오기 보류(메인 서버가 아직 꺼져 있음) — 30초 뒤 다시");
            return;
        }
        int n = importJson(body);
        done = true;
        log.info("메인 서버의 물건 데이터를 물품 DB 로 옮겼어요: {}줄", n);
    }

    /** 옮긴 줄 수(테스트에서 직접 부른다) */
    @SuppressWarnings("unchecked")
    public int importJson(String json) {
        Map<String, Object> all = Json.readMap(json);
        Integer total = tx.execute(s -> {
            int count = 0;
            for (String table : TABLES) {
                Object raw = all.get(table);
                if (!(raw instanceof List<?> list)) {
                    continue;
                }
                for (Object o : list) {
                    insert(table, (Map<String, Object>) o);
                    count++;
                }
            }
            for (String table : IDENTITY) {
                Long max = jdbc.queryForObject("select coalesce(max(id), 0) from " + table, Long.class);
                jdbc.execute("alter table " + table + " alter column id restart with " + (max + 1));
            }
            return count;
        });
        return total == null ? 0 : total;
    }

    private void insert(String table, Map<String, Object> row) {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String col = e.getKey().toLowerCase(Locale.ROOT);
            if (!col.matches("[a-z_]+")) { // 열 이름은 글자 · 밑줄만(SQL 에 그대로 들어가므로)
                continue;
            }
            cols.add(col);
            Object v = e.getValue();
            vals.add(v != null && TIMES.contains(col) ? OffsetDateTime.parse(v.toString()) : v);
        }
        String sql = "insert into " + table + " (" + String.join(", ", cols) + ") values ("
                + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")";
        jdbc.update(sql, vals.toArray());
    }
}
