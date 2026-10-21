package com.sbada;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import com.sbada.common.Json;

/**
 * 실제 서버를 임의 포트로 띄우고 WebSocket 으로 채팅·실시간 알림이 오가는지 확인한다.
 * (MockMvc 는 HTTP 101 업그레이드를 흉내 내지 못하므로 JDK HttpClient 의 WebSocket 을 쓴다)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-ws-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.upload.dir=./build/test-data/uploads"
})
class WebSocketChatTest {

    @Autowired
    Environment env;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Test
    void chatMessageAndNotificationArriveInRealTime() throws Exception {
        String base = "http://127.0.0.1:" + env.getProperty("local.server.port");
        String ownerToken = signupAndLogin(base, "wsowner");
        String buyerToken = signupAndLogin(base, "wsbuyer");

        // 판매자 물건 등록(사진 없이 만들 수 없으므로 사진 → 등록)
        long ownerImage = uploadPng(base, ownerToken);
        long ownerItem = createItem(base, ownerToken, ownerImage, "캠핑 랜턴", "SPORTS");
        long buyerImage = uploadPng(base, buyerToken);
        long buyerItem = createItem(base, buyerToken, buyerImage, "등산 스틱", "SPORTS");

        // 판매자가 WebSocket 에 접속해 있다
        BlockingQueue<Map<String, Object>> ownerInbox = new LinkedBlockingQueue<>();
        WebSocket ownerWs = connect(base, ownerToken, ownerInbox);
        Map<String, Object> hello = poll(ownerInbox, "hello");
        assertNotNull(hello, "연결 직후 hello 를 받아야 한다");

        // 잘못된 토큰은 연결 거절(401)
        try {
            connect(base, "not-a-token", new LinkedBlockingQueue<>());
            throw new AssertionError("무효 토큰으로 연결되면 안 된다");
        } catch (CompletionException expected) {
            // OK
        }

        // 구매자가 채팅방을 열고 WebSocket 으로 메시지를 보낸다
        Map<String, Object> room = postJson(base + "/api/chats", buyerToken, "{\"itemId\":" + ownerItem + "}");
        long roomId = ((Number) room.get("id")).longValue();
        BlockingQueue<Map<String, Object>> buyerInbox = new LinkedBlockingQueue<>();
        WebSocket buyerWs = connect(base, buyerToken, buyerInbox);
        poll(buyerInbox, "hello");
        buyerWs.sendText(Json.write(Map.of("type", "chat.send", "roomId", roomId,
                "content", "랜턴 아직 있나요?", "clientMsgId", "c-1")), true).join();

        Map<String, Object> received = poll(ownerInbox, "chat.message");
        assertNotNull(received, "판매자가 실시간으로 메시지를 받아야 한다");
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) received.get("message");
        assertEquals("랜턴 아직 있나요?", message.get("content"));
        Map<String, Object> echo = poll(buyerInbox, "chat.message");
        assertEquals("c-1", echo.get("clientMsgId"), "보낸 사람도 clientMsgId 로 전송 확인을 받는다");

        // 구매자가 '바로 바꾸기' → 판매자에게 notification 이벤트가 실시간으로 온다
        postJson(base + "/api/exchanges", buyerToken,
                "{\"targetItemId\":" + ownerItem + ",\"offeredItemId\":" + buyerItem + "}");
        Map<String, Object> noti = poll(ownerInbox, "notification");
        assertNotNull(noti, "교환 요청 알림이 WebSocket 으로 와야 한다");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) noti.get("notification");
        assertEquals("EXCHANGE_REQUESTED", body.get("type"));

        // 방에 없는 사람이 보내면 error 프레임
        buyerWs.sendText("{\"type\":\"chat.send\",\"roomId\":999999,\"content\":\"hi\",\"clientMsgId\":\"x\"}", true).join();
        Map<String, Object> error = poll(buyerInbox, "error");
        assertEquals("ROOM_NOT_FOUND", error.get("code"));

        ownerWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        buyerWs.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    private WebSocket connect(String base, String token, BlockingQueue<Map<String, Object>> inbox) {
        String url = base.replace("http://", "ws://") + "/ws?token=" + token;
        return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            inbox.add(Json.readMap(buf.toString()));
                            buf.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }
                }).join();
    }

    private static Map<String, Object> poll(BlockingQueue<Map<String, Object>> inbox, String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> e = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (e != null && type.equals(e.get("type"))) {
                return e;
            }
        }
        return null;
    }

    private String signupAndLogin(String base, String prefix) throws Exception {
        String username = (prefix + System.nanoTime() % 1_000_000).toLowerCase();
        send(HttpRequest.newBuilder(URI.create(base + "/api/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"study-1234\",\"nickname\":\"웹소켓\"}",
                        StandardCharsets.UTF_8)).build(), 201);
        String login = send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"study-1234\"}")).build(), 200);
        return Json.readMap(login).get("accessToken").toString();
    }

    private long uploadPng(String base, String token) throws Exception {
        byte[] png = TestSupport.png(java.awt.Color.GREEN);
        String boundary = "sbadaBoundary" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + png.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(png, 0, all, head.length, png.length);
        System.arraycopy(tail, 0, all, head.length + png.length, tail.length);
        String res = send(HttpRequest.newBuilder(URI.create(base + "/api/images"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(all)).build(), 201);
        return ((Number) Json.readMap(res).get("id")).longValue();
    }

    private long createItem(String base, String token, long imageId, String title, String category) throws Exception {
        Map<String, Object> item = postJson(base + "/api/items", token,
                "{\"title\":\"" + title + "\",\"category\":\"" + category + "\",\"condition\":\"GOOD\",\"imageIds\":["
                        + imageId + "]}");
        return ((Number) item.get("id")).longValue();
    }

    private Map<String, Object> postJson(String url, String token, String json) throws Exception {
        String res = send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(), -1);
        return Json.readMap(res);
    }

    private String send(HttpRequest req, int expected) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (expected > 0) {
            assertEquals(expected, res.statusCode(), res.body());
        } else {
            assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
        }
        return res.body();
    }
}
