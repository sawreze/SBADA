package com.sbada;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

/** 테스트에서 반복하는 요청(가입·로그인·사진 올리기·분석·등록)을 모아 둔다. */
final class TestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestSupport() {
    }

    record Account(long id, String username, String token) {
        String bearer() {
            return "Bearer " + token;
        }
    }

    static Account newAccount(MockMvc mvc, String prefix) throws Exception {
        String username = (prefix + SEQ.incrementAndGet() + "_" + System.nanoTime() % 100000).toLowerCase();
        if (username.length() > 20) {
            username = username.substring(0, 20);
        }
        String signup = """
                {"username":"%s","password":"study-1234","nickname":"%s"}
                """.formatted(username, "테스터" + SEQ.get());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signup))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"study-1234\"}".formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.user.id");
        String token = JsonPath.read(body, "$.accessToken");
        return new Account(id.longValue(), username, token);
    }

    /**
     * 물건 등록(2026-11-25 MSA 2단계): 물건은 물품 서버에 있으므로 메인 서버 테스트에서는 가짜 물품 서비스(FakeServices)에 넣는다.
     * 사진 업로드 · AI 분석 · 등록 흐름 자체는 물품 서버 테스트(sbada-item ItemServiceApiTest)가 확인한다.
     */
    static long createItem(MockMvc mvc, Account a, String title, String category, String condition,
                           String wishJsonArray) {
        return FakeServices.addItem(a.id(), title, category, condition, 100_000);
    }

    static long createItem(Account a, String title, String category, int price) {
        return FakeServices.addItem(a.id(), title, category, "GOOD", price);
    }
}
