package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 기기 토큰 등록/지우기 POST /api/devices(/remove) — 2026-11-25 ItemSummaryApiTest 에서 떼어 냄(요약은 물품 서버로) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-devices-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class DeviceTokenApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void deviceTokenRegisterAndRemoveOnlyMine() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "devone");
        TestSupport.Account b = TestSupport.newAccount(mvc, "devtwo");
        String body = "{\"token\":\"test-fcm-token-123\",\"platform\":\"android\"}";
        mvc.perform(post("/api/devices").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true))
                .andExpect(jsonPath("$.fcmEnabled").value(false));
        // 남의 토큰 지우기 → 204 지만 아무 일 없음, 내 토큰 → 204
        mvc.perform(post("/api/devices/remove").header("Authorization", b.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/devices/remove").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/devices/remove").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
