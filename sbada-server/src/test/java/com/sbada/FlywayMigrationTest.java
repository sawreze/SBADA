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
                "select count(*) from \"flyway_schema_history\" where \"success\" = true and \"version\" in ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10')",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(applied).isEqualTo(10);
        // V2 시연 계정 4명 + V9 전국 판매자 135명
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from app_users", Integer.class))
                .isEqualTo(139);
        // V4: V2 의 표본 16개를 지우고 상품 50개(사진 50장)로 다시 만든다 + V9 전국 상품 500개 · V10 사진 500장
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from items", Integer.class))
                .isEqualTo(550);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from item_images", Integer.class))
                .isEqualTo(550);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from item_images where stored_name like 'seed-q%'", Integer.class)).isEqualTo(500);
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

        // 추천 피드 후보 = 남의 물건(550개 중 민지 것 12개를 뺀 538개) 중 최대 300개(서버가 추천 후보 수를 300개로 제한)
        mvc.perform(get("/api/feed").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").value(300))
                .andExpect(jsonPath("$.items.length()", greaterThan(0)));

        // 채팅방에는 교환 제안 메시지가 있고, 민지 입장에서 안 읽은 1개
        mvc.perform(get("/api/chats").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms", hasSize(1)))
                .andExpect(jsonPath("$.rooms[0].unread").value(1));
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
