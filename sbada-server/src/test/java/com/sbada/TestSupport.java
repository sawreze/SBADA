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

    static byte[] png(Color color) throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static long uploadImage(MockMvc mvc, Account a) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", png(Color.ORANGE));
        String body = mvc.perform(multipart("/api/images").file(file).header("Authorization", a.bearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.id");
        return id.longValue();
    }

    static long analyze(MockMvc mvc, Account a, long imageId, String category, String condition) throws Exception {
        String body = mvc.perform(post("/api/ai/analyze").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":%d,\"category\":\"%s\",\"condition\":\"%s\"}"
                                .formatted(imageId, category, condition)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.analysisId");
        return id.longValue();
    }

    /** 사진 업로드 → AI 분석 → 등록까지 한 번에. 등록된 물건 번호를 돌려준다. */
    static long createItem(MockMvc mvc, Account a, String title, String category, String condition,
                           String wishJsonArray) throws Exception {
        long imageId = uploadImage(mvc, a);
        long analysisId = analyze(mvc, a, imageId, category, condition);
        String json = """
                {"title":"%s","description":"테스트 물건","category":"%s","condition":"%s",
                 "imageIds":[%d],"analysisId":%d,"wishCategories":%s}
                """.formatted(title, category, condition, imageId, analysisId, wishJsonArray);
        String body = mvc.perform(post("/api/items").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number id = JsonPath.read(body, "$.id");
        return id.longValue();
    }
}
