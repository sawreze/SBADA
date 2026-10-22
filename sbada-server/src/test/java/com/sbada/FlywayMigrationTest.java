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
    JdbcTemplate jdbc;

    @Test
    void migrationsCreateSchemaAndDemoData() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from \"flyway_schema_history\" where \"success\" = true and \"version\" in ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15')",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(applied).isEqualTo(15);
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

    @Test
    void demoAccountsWorkLikeBefore() throws Exception {
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

        // 추천 피드 후보 = 남의 물건(764개 중 민지 것 12개를 뺀 752개) 중 최대 300개(서버가 추천 후보 수를 300개로 제한)
        mvc.perform(get("/api/feed").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").value(300))
                .andExpect(jsonPath("$.items.length()", greaterThan(0)));

        // 채팅방: 도현의 교환 제안(가장 최근, 민지 입장에서 안 읽은 1개) + V12 admin 의 일렉 기타에 민지가 물어본 채팅
        mvc.perform(get("/api/chats").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms", hasSize(2)))
                .andExpect(jsonPath("$.rooms[0].unread").value(1));

        // 홈 범위(서울 서대문구 = V11 에서만 8개): 피드 후보도 그 안에서만
        mvc.perform(get("/api/feed").param("area", "서울 서대문구").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").value(8));
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

        String mine = mvc.perform(get("/api/me/items").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(14)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        java.util.List<Integer> prices = JsonPath.read(mine, "$.items[*].price");
        org.assertj.core.api.Assertions.assertThat(prices).contains(7000, 340000)
                .anyMatch(p -> p < 10000).anyMatch(p -> p >= 10000 && p < 50000)
                .anyMatch(p -> p >= 50000 && p < 200000).anyMatch(p -> p >= 200000);
        java.util.List<java.util.List<String>> photos = JsonPath.read(mine, "$.items[*].imageUrls");
        org.assertj.core.api.Assertions.assertThat(photos).allMatch(urls -> urls.size() == 1);

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

    @Test
    void areaRangeCountsAndFilters() throws Exception {
        // 전국 = 764(V4 50 + V9 500 + V11 200 + V12 14), '전국'이라고 보내도 전국
        mvc.perform(get("/api/items/count")).andExpect(jsonPath("$.count").value(764));
        mvc.perform(get("/api/items/count").param("area", "전국")).andExpect(jsonPath("$.count").value(764));
        // 서대문구는 V11 에서만 8개(동네 2곳 × 4) · 그중 신촌동 4개
        mvc.perform(get("/api/items/count").param("area", "서울 서대문구"))
                .andExpect(jsonPath("$.area").value("서울 서대문구"))
                .andExpect(jsonPath("$.count").value(8));
        mvc.perform(get("/api/items/count").param("area", "서울 서대문구 신촌동")).andExpect(jsonPath("$.count").value(4));
        // 단어 단위 비교: '중구'(서울 중구)가 '해운대구' · '수성구' 같은 이름 속 글자에 걸리지 않는다
        Integer jungguDb = jdbc.queryForObject("""
                select count(*) from items i join app_users u on u.id = i.owner_id
                where i.status = 'AVAILABLE' and (u.region like '% 중구 %' or u.region like '중구 %')""", Integer.class);
        mvc.perform(get("/api/items/count").param("area", "중구")).andExpect(jsonPath("$.count").value(jungguDb));
        // 목록도 같은 범위로 거른다
        mvc.perform(get("/api/items").param("area", "서울 서대문구").param("limit", "100"))
                .andExpect(jsonPath("$.items", hasSize(8)))
                .andExpect(jsonPath("$.items[0].owner.region").value(org.hamcrest.Matchers.startsWith("서울 서대문구 ")));
        // LIKE 특수문자는 막는다
        mvc.perform(get("/api/items/count").param("area", "서울%")).andExpect(status().isBadRequest());
    }

    @Test
    void seedImagesAreServed() throws Exception {
        String items = mvc.perform(get("/api/items").param("q", "이어폰"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String url = JsonPath.read(items, "$.items[0].imageUrls[0]");
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }
}
