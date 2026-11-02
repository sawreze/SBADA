package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 아이폰 APNs 직접 토큰 등록(platform=ios-apns): APNs 키가 설정돼 있으면 fcmEnabled(=이 기기로 보낼 수 있음)=true */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-apns-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.push.apns.enabled=true",
        "sbada.push.apns.key-file=./build/test-data/AuthKey_NOT_USED.p8",
        "sbada.push.apns.key-id=TESTKEY123",
        "sbada.push.apns.team-id=TESTTEAM12",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class ApnsDeviceApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void iosApnsTokenRegistersAndReportsPushReady() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "apnsuser");
        String apnsToken = "a".repeat(64);
        mvc.perform(post("/api/devices").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + apnsToken + "\",\"platform\":\"ios-apns\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.fcmEnabled").value(true));
        // 안드로이드(FCM)는 Firebase 설정이 없으니 false
        mvc.perform(post("/api/devices").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"android-token-1\",\"platform\":\"android\"}"))
                .andExpect(jsonPath("$.fcmEnabled").value(false));
        // 알림 설정 화면: 둘 중 하나라도 되면 pushReady
        mvc.perform(get("/api/me/notification-settings").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.pushReady").value(true));
    }
}
