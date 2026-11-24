package com.sbada.gateway;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MSA 2단계 데이터 이전: 메인 서버 DB 의 옛 물건 표를 물품 서버로 넘길 JSON (2026-11-25)
 *  - 물품 서버가 처음 켜질 때(자기 DB 가 비어 있을 때) 한 번 GET /internal/legacy/items-export 로 가져간다.
 *  - 메인 서버는 이 표들을 더 이상 쓰지 않지만 지우지 않는다(되돌릴 수 있게). 번호(id)는 그대로 넘긴다.
 *  - 판매자(sellers) = 회원의 번호 · 닉네임 · 동네만(비밀번호 등은 넘기지 않음).
 */
@Component
public class LegacyExport {

    private static final List<String> TABLES = List.of("items", "item_wish_categories", "item_images",
            "ai_analyses", "item_likes");

    private final JdbcTemplate jdbc;

    public LegacyExport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> export() {
        Map<String, Object> out = new LinkedHashMap<>();
        String now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC).toString();
        List<Map<String, Object>> sellers = new ArrayList<>();
        for (Map<String, Object> u : jdbc.queryForList(
                "select id, nickname, region, second_region from app_users order by id")) {
            Map<String, Object> s = clean(u);
            s.put("updated_at", now);
            sellers.add(s);
        }
        out.put("sellers", sellers);
        for (String t : TABLES) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : jdbc.queryForList("select * from " + t)) {
                rows.add(clean(r));
            }
            out.put(t, rows);
        }
        return out;
    }

    /** 열 이름은 소문자, 시각 · enum 등은 문자열로(숫자 · 참거짓 · 글자는 그대로) */
    private static Map<String, Object> clean(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        row.forEach((k, v) -> m.put(k.toLowerCase(java.util.Locale.ROOT),
                v == null || v instanceof Number || v instanceof Boolean || v instanceof String ? v : v.toString()));
        return m;
    }
}
