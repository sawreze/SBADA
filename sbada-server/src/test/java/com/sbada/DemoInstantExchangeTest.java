package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 시연 예외(sbada.demo.instant-exchange-users=admin): admin 이 '바로 바꾸기'를 보내면 상대 수락 없이 바로 성사.
 * 다른 사람(junho)은 원래대로 '요청됨'에서 상대의 수락을 기다린다.
 * 교환하면 물건 상태가 바뀌어서 다른 테스트의 개수에 영향이 없도록 DB 를 따로 쓴다(V1~V15 시연 데이터 그대로).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-instant-exchange-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/instant-uploads",
        "sbada.demo.instant-exchange-users=admin"
})
@AutoConfigureMockMvc
class DemoInstantExchangeTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    String login(String username) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"sbada1234\"}".formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + JsonPath.read(body, "$.accessToken");
    }

    long itemOf(String username, String title) {
        return jdbc.queryForObject("""
                select i.id from items i join app_users u on u.id = i.owner_id
                where u.username = ? and i.title = ?""", Long.class, username, title);
    }

    String statusOf(long itemId) {
        return jdbc.queryForObject("select status from items where id = ?", String.class, itemId);
    }

    @Test
    void adminExchangeCompletesRightAwayOthersStillWait() throws Exception {
        // admin 'USB 메모리' → 민지 '필름 카메라': 수락 없이 바로 ACCEPTED, 두 물건 모두 교환 완료
        long usb = itemOf("admin", "USB 메모리");
        long film = itemOf("minji", "필름 카메라");
        mvc.perform(post("/api/exchanges").header("Authorization", login("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(film, usb)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(statusOf(usb)).isEqualTo("TRADED");
        assertThat(statusOf(film)).isEqualTo("TRADED");
        // 민지에게는 '바로 성사' 알림, 채팅방에는 성사 메시지
        assertThat(jdbc.queryForObject("""
                select count(*) from notifications n join app_users u on u.id = n.user_id
                where u.username = 'minji' and n.noti_type = 'EXCHANGE_ACCEPTED'""", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from chat_messages where msg_type = 'SYSTEM' and content like '%교환이 성사되었어요%'",
                Integer.class)).isGreaterThanOrEqualTo(1);

        // 준호(시연 예외 아님)는 원래대로 요청됨 → 물건은 그대로 교환 가능
        long junhoItem = itemOf("junho", "보드게임");
        long seoyeonItem = itemOf("seoyeon", "우산");
        mvc.perform(post("/api/exchanges").header("Authorization", login("junho"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d}".formatted(seoyeonItem, junhoItem)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
        assertThat(statusOf(seoyeonItem)).isEqualTo("AVAILABLE");
    }
}
