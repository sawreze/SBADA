package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/**
 * Flyway: 빈 DB에 V1(테이블) · V2(시연 데이터)가 적용되고, JPA 엔티티와 테이블이 맞는지(validate) 확인한다.
 * 다른 테스트와 달리 ddl-auto 를 validate 로 두어 실제 서버와 같은 방식으로 띄운다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-flyway-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sbada.seed.enabled=true",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/flyway-uploads"
})
@AutoConfigureMockMvc
class FlywayMigrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    com.sbada.gateway.LegacyExport legacy;
    @Autowired
    com.sbada.gateway.InternalToken internal;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsCreateSchemaAndDemoData() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"success\" = true and \"version\" in ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15', '16', '17', '18', '19')",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(applied).isEqualTo(19);
        // V2 시연 계정 4명 + V9 전국 판매자 135명 + V11 서울 판매자 50명 + V12 admin
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from app_users", Integer.class))
                .isEqualTo(190);
        // V4: V2 의 표본 16개를 지우고 상품 50개(사진 50장)로 다시 만든다 + V9 전국 상품 500개 · V10 사진 500장 + V11 서울 200개(사진 200장) + V12 admin 14개(사진 seed-a01~a14)
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from items", Integer.class))
                .isEqualTo(764);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from item_images", Integer.class))
                .isEqualTo(764);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from item_images where stored_name like 'seed-q%'", Integer.class)).isEqualTo(500);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from item_images where stored_name like 'seed-r%'", Integer.class)).isEqualTo(200);
        // V11: 서울 판매자 50명이 서울 25개 구에 2명씩(구마다 상품 8개)
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(distinct substring(region, 4, position(' ' in substring(region, 4)) - 1)) from app_users where username like 'sw%' and region like '서울 %'",
                Integer.class)).isEqualTo(25);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from items i join app_users u on u.id = i.owner_id where u.username like 'sw%'", Integer.class))
                .isEqualTo(200);
        // V9: 판매자가 인구 비례로 전국 17개 시도에 퍼져 있다
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(distinct substring(region, 1, 2)) from app_users where username like 'nw%'", Integer.class))
                .isEqualTo(17);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from item_images where stored_name like 'seed-p%'", Integer.class)).isEqualTo(50);
    }

    /** 물품 서버가 옛 데이터를 가져간 상태를 흉내: 메인 DB 의 옛 물건 표 → 가짜 물품 서비스 */
    @SuppressWarnings("unchecked")
    private void seedFakeItemService() {
        for (Object o : (java.util.List<Object>) legacy.export().get("items")) {
            java.util.Map<String, Object> r = (java.util.Map<String, Object>) o;
            long id = ((Number) r.get("id")).longValue();
            FakeServices.ITEMS.put(id, new FakeServices.FakeItem(id, ((Number) r.get("owner_id")).longValue(),
                    (String) r.get("title"), ((Number) r.get("ai_price")).intValue(), String.valueOf(r.get("category")),
                    String.valueOf(r.get("item_condition")), String.valueOf(r.get("status"))));
        }
    }

    @Test
    void demoAccountsWorkLikeBefore() throws Exception {
        seedFakeItemService();
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"minji\",\"password\":\"sbada1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.nickname").value("민지"))
                .andExpect(jsonPath("$.user.onboarded").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String bearer = "Bearer " + JsonPath.read(login, "$.accessToken");

        // 민지는 도현에게서 받은 교환 요청 1건과 안 읽은 알림 1개로 시작한다
        mvc.perform(get("/api/exchanges").param("box", "received").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchanges", hasSize(1)))
                .andExpect(jsonPath("$.exchanges[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$.exchanges[0].offeredItem.title").value("모니터"))
                .andExpect(jsonPath("$.exchanges[0].targetItem.title").value("무선 이어폰"))
                .andExpect(jsonPath("$.exchanges[0].chatRoomId").isNumber());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1));

        // 채팅방: 도현의 교환 제안(가장 최근, 민지 입장에서 안 읽은 1개) + V12 admin 의 일렉 기타에 민지가 물어본 채팅
        mvc.perform(get("/api/chats").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms", hasSize(2)))
                .andExpect(jsonPath("$.rooms[0].unread").value(1));

    }

    /** V12 시연 계정 admin: 가격대별 물건 14개 · 채팅 7개(안 읽음 5) · 받은 요청 2 · 보낸 요청 3 · 안 읽은 알림 3 */
    @Test
    void adminDemoAccountIsReady() throws Exception {
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"sbada1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.nickname").value("스바다"))
                .andExpect(jsonPath("$.user.region").value("서울 마포구 망원동"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String bearer = "Bearer " + JsonPath.read(login, "$.accessToken");

        // admin 의 물건 14개(가격대별) — 물건은 물품 서버가 옮겨 가는 옛 표에서 확인(2026-11-25 MSA 2단계)
        seedFakeItemService();
        java.util.List<Integer> prices = jdbc.queryForList("""
                select i.ai_price from items i join app_users u on u.id = i.owner_id
                where u.username = 'admin' and i.status <> 'HIDDEN'""", Integer.class);
        org.assertj.core.api.Assertions.assertThat(prices).hasSize(14);
        org.assertj.core.api.Assertions.assertThat(prices).contains(7000, 340000)
                .anyMatch(p -> p < 10000).anyMatch(p -> p >= 10000 && p < 50000)
                .anyMatch(p -> p >= 50000 && p < 200000).anyMatch(p -> p >= 200000);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from item_images m join items i on i.id = m.item_id join app_users u on u.id = i.owner_id
                where u.username = 'admin'""", Integer.class)).isEqualTo(14);

        String chats = mvc.perform(get("/api/chats").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms", hasSize(7)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.List<Integer> unread = JsonPath.read(chats, "$.rooms[*].unread");
        org.assertj.core.api.Assertions.assertThat(unread.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5);

        mvc.perform(get("/api/exchanges").param("box", "received").header("Authorization", bearer))
                .andExpect(jsonPath("$.exchanges", hasSize(2)));
        mvc.perform(get("/api/exchanges").param("box", "sent").header("Authorization", bearer))
                .andExpect(jsonPath("$.exchanges", hasSize(3)));
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", bearer))
                .andExpect(jsonPath("$.unread").value(3));
    }

    /** 물품 서버로 넘길 옛 데이터(GET /internal/legacy/items-export): 물건 764 · 판매자(회원) 전원 · 사진 · 원하는 카테고리 */
    @Test
    @SuppressWarnings("unchecked")
    void legacyExportCarriesSeedItems() throws Exception {
        java.util.Map<String, Object> ex = legacy.export();
        org.assertj.core.api.Assertions.assertThat((java.util.List<Object>) ex.get("items")).hasSize(764);
        org.assertj.core.api.Assertions.assertThat((java.util.List<Object>) ex.get("sellers"))
                .hasSize(jdbc.queryForObject("select count(*) from app_users", Integer.class));
        org.assertj.core.api.Assertions.assertThat((java.util.List<Object>) ex.get("item_images"))
                .hasSize(jdbc.queryForObject("select count(*) from item_images", Integer.class));
        java.util.Map<String, Object> first = (java.util.Map<String, Object>) ((java.util.List<Object>) ex.get("items")).get(0);
        org.assertj.core.api.Assertions.assertThat(first).containsKeys("id", "owner_id", "title", "ai_price", "created_at");
        org.assertj.core.api.Assertions.assertThat(first.get("created_at")).isInstanceOf(String.class);
        // 내부 토큰 없이는 못 가져간다
        mvc.perform(get("/internal/legacy/items-export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/legacy/items-export").header(com.sbada.gateway.InternalToken.HEADER, internal.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(764)));
    }
}
