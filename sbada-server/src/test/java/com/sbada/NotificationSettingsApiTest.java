package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.sbada.notification.NotificationType;

/** 알림 설정 GET/PUT /api/me/notification-settings · 찜 알림 ITEM_LIKED (V14) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-noti-settings-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class NotificationSettingsApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void settingsDefaultOnAndChangeOnlyWhatIsSent() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "notiset");
        mvc.perform(get("/api/me/notification-settings")).andExpect(status().isUnauthorized());
        // 기본: 모두 켜짐, 서버에 Firebase 설정이 없으면 pushReady=false
        mvc.perform(get("/api/me/notification-settings").header("Authorization", a.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.like").value(true))
                .andExpect(jsonPath("$.chat").value(true))
                .andExpect(jsonPath("$.exchange").value(true))
                .andExpect(jsonPath("$.pushReady").value(false));
        // 채팅만 끔 → 나머지는 그대로
        mvc.perform(put("/api/me/notification-settings").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"chat\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.like").value(true))
                .andExpect(jsonPath("$.chat").value(false))
                .andExpect(jsonPath("$.exchange").value(true));
        mvc.perform(put("/api/me/notification-settings").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"like\":false,\"exchange\":false,\"chat\":true}"))
                .andExpect(jsonPath("$.like").value(false))
                .andExpect(jsonPath("$.chat").value(true))
                .andExpect(jsonPath("$.exchange").value(false));
        mvc.perform(get("/api/me/notification-settings").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.like").value(false))
                .andExpect(jsonPath("$.exchange").value(false));
    }

    @Test
    void likeNotifiesOwnerOnceAndStaysInInboxEvenWhenOff() throws Exception {
        TestSupport.Account owner = TestSupport.newAccount(mvc, "likenoti");
        TestSupport.Account fan = TestSupport.newAccount(mvc, "likefan2");
        long itemId = TestSupport.createItem(mvc, owner, "찜 알림 테스트 시계", "DIGITAL", "GOOD", "[]");

        // 두 번 찜해도 알림은 한 번
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/items/" + itemId + "/like").header("Authorization", fan.bearer()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/notifications").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.notifications[?(@.type == 'ITEM_LIKED')].refId").value(
                        org.hamcrest.Matchers.contains((int) itemId)))
                .andExpect(jsonPath("$.notifications[?(@.type == 'ITEM_LIKED')].body").value(
                        org.hamcrest.Matchers.contains(org.hamcrest.Matchers.containsString("찜 알림 테스트 시계"))));

        // 찜 알림을 꺼도 알림함 기록은 남는다(푸시 · 배너만 안 보냄)
        mvc.perform(put("/api/me/notification-settings").header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"like\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/items/" + itemId + "/like").header("Authorization", fan.bearer()))
                .andExpect(status().isOk()); // 이미 찜함 → 새 알림 없음
        mvc.perform(get("/api/notifications").header("Authorization", owner.bearer()))
                .andExpect(jsonPath("$.notifications[?(@.type == 'ITEM_LIKED')]",
                        org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void notificationTypesBelongToTheRightSwitch() {
        assertThat(NotificationType.ITEM_LIKED.settingKey()).isEqualTo("like");
        assertThat(NotificationType.CHAT_MESSAGE.settingKey()).isEqualTo("chat");
        assertThat(NotificationType.EXCHANGE_REQUESTED.settingKey()).isEqualTo("exchange");
        assertThat(NotificationType.EXCHANGE_ACCEPTED.settingKey()).isEqualTo("exchange");
        assertThat(NotificationType.MATCH_SUGGESTED.settingKey()).isNull(); // 설정에 없는 알림은 항상 보냄
        assertThat(NotificationType.ITEM_HELD.settingKey()).isNull();
    }
}
