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

    /**
     * 물품 서버에는 회원 가입이 없다(회원은 메인 서버). 가짜 회원 서비스(ItemTestConfig.USERS)에 넣고,
     * 메인 서버와 같은 키 파일로 로그인 토큰을 직접 만든다. (2026-11-25 MSA 2단계)
     */
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong(20_000);

    static Account newAccount(MockMvc mvc, String prefix) throws Exception {
        long id = IDS.incrementAndGet();
        String username = (prefix + SEQ.incrementAndGet()).toLowerCase();
        ItemTestConfig.USERS.put(id, new String[] {"테스터" + SEQ.get(), "서울 마포구 망원동"});
        String b64 = java.nio.file.Files.readString(java.nio.file.Path.of("./build/test-data/.jwt-secret"),
                StandardCharsets.US_ASCII).trim();
        String token = io.jsonwebtoken.Jwts.builder().issuer("sbada").subject(Long.toString(id))
                .issuedAt(new java.util.Date())
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(3600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(java.util.Base64.getDecoder().decode(b64)))
                .compact();
        return new Account(id, username, token);
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
