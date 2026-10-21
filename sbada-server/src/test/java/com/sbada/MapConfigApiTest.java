package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 지도 설정 API: 로그인 없이 열리고, 설정 파일의 따옴표는 지운 키를 준다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-map-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads",
        "sbada.map.provider=kakao",
        "sbada.map.kakao-js-key=\"test-js-key\""
})
@AutoConfigureMockMvc
class MapConfigApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void mapConfigIsPublicAndStripsQuotes() throws Exception {
        mvc.perform(get("/api/config/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("kakao"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.kakaoJsKey").value("test-js-key"));
    }

    /** 기본(osm): 키 없이 항상 지도 사용 가능, 카카오 키는 내보내지 않는다 */
    @Test
    void osmIsDefaultAndNeedsNoKey() {
        var body = new com.sbada.web.MapConfigController("osm", "some-key").mapConfig();
        org.assertj.core.api.Assertions.assertThat(body.get("provider")).isEqualTo("osm");
        org.assertj.core.api.Assertions.assertThat(body.get("configured")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(body.get("kakaoJsKey")).isNull();
        var empty = new com.sbada.web.MapConfigController("", "").mapConfig();
        org.assertj.core.api.Assertions.assertThat(empty.get("provider")).isEqualTo("osm");
    }
}
