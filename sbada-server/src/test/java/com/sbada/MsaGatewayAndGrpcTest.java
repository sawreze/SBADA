package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sbada.gateway.InternalToken;
import com.sbada.grpc.exchange.v1.ExchangeServiceGrpc;
import com.sbada.grpc.exchange.v1.GetExchangeRequest;
import com.sbada.grpc.user.v1.GetUsersRequest;
import com.sbada.grpc.user.v1.RecommendContextRequest;
import com.sbada.grpc.user.v1.UserServiceGrpc;
import com.sun.net.httpserver.HttpServer;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;

/**
 * MSA 2단계(2026-11-25) 메인 서버 쪽 연결 확인
 *  ① 게이트웨이: /api/items/** · /api/images/** · /graphql 을 물품 서버(가짜 HTTP)로 본문 · 토큰 · 형식 그대로 넘김, 꺼져 있으면 503
 *  ② 배달부(gRPC) 서버: UserService(GetUsers · GetRecommendContext) · ExchangeService(GetExchange) + 내부 토큰 확인
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-msa-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "sbada.seed.enabled=false",
        "sbada.push.fcm.enabled=false",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.upload.dir=./build/test-data/uploads"
})
@AutoConfigureMockMvc
class MsaGatewayAndGrpcTest {

    /** 가짜 물품 서버(HTTP): 받은 요청을 기록하고 정해진 답을 준다 */
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer FAKE_ITEM;
    private static ManagedChannel channel;

    static {
        try {
            FAKE_ITEM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        FAKE_ITEM.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            SEEN.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders()
                    .getFirst("Authorization") + " type=" + ex.getRequestHeaders().getFirst("Content-Type")
                    + " bytes=" + body.length);
            byte[] out;
            String type;
            if (ex.getRequestURI().getPath().startsWith("/api/images/")) {
                out = new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
                type = "image/jpeg";
                ex.getResponseHeaders().add("Cache-Control", "max-age=86400");
            } else {
                out = "{\"items\":[{\"id\":1,\"title\":\"물품 서버에서 온 물건\"}]}".getBytes(StandardCharsets.UTF_8);
                type = "application/json";
            }
            ex.getResponseHeaders().add("Content-Type", type);
            ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/api/items/404") ? 404 : 200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        FAKE_ITEM.start();
    }

    @DynamicPropertySource
    static void itemUrl(DynamicPropertyRegistry registry) {
        registry.add("sbada.services.item.url", () -> "http://127.0.0.1:" + FAKE_ITEM.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        FAKE_ITEM.stop(0);
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    InternalToken internal;

    @Test
    void gatewayForwardsItemRequestsAsIs() throws Exception {
        TestSupport.Account a = TestSupport.newAccount(mvc, "gw");
        mvc.perform(get("/api/items").param("area", "서울 마포구").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("물품 서버에서 온 물건"));
        assertThat(SEEN).anySatisfy(s -> assertThat(s).startsWith("GET /api/items?area=").contains("limit=5"));

        // GraphQL: 본문 · 토큰 그대로
        String q = "{\"query\":\"{ items { id title } }\"}";
        mvc.perform(post("/graphql").header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON).content(q))
                .andExpect(status().isOk());
        assertThat(SEEN).anySatisfy(s -> assertThat(s).startsWith("POST /graphql").contains("auth=" + a.bearer())
                .contains("type=application/json").contains("bytes=" + q.getBytes(StandardCharsets.UTF_8).length));

        // 사진 올리기(multipart)도 바이트 그대로, 사진 보기는 형식 · 캐시 헤더까지
        byte[] multipart = "--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n\r\nPNG\r\n--b--\r\n"
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/images").header("Authorization", a.bearer())
                        .contentType("multipart/form-data; boundary=b").content(multipart))
                .andExpect(status().isOk());
        assertThat(SEEN).anySatisfy(s -> assertThat(s).startsWith("POST /api/images")
                .contains("multipart/form-data; boundary=b").contains("bytes=" + multipart.length));
        mvc.perform(get("/api/images/7"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=86400"));
        mvc.perform(get("/api/items/404")).andExpect(status().isNotFound()); // 상태 코드도 그대로
    }

    private Metadata token() {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of(InternalToken.GRPC_KEY, Metadata.ASCII_STRING_MARSHALLER), internal.value());
        return md;
    }

    private ManagedChannel channel() {
        if (channel == null) {
            channel = InProcessChannelBuilder.forName("main-test").directExecutor().build();
        }
        return channel;
    }

    @Test
    void grpcUserAndExchangeServices() throws Exception {
        TestSupport.Account seller = TestSupport.newAccount(mvc, "gs");
        TestSupport.Account buyer = TestSupport.newAccount(mvc, "gb");
        var users = UserServiceGrpc.newBlockingStub(channel());
        assertThatThrownBy(() -> users.getUsers(GetUsersRequest.newBuilder().addIds(seller.id()).build()))
                .isInstanceOf(StatusRuntimeException.class).hasMessageContaining("UNAUTHENTICATED");

        var authed = users.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(token()));
        var res = authed.getUsers(GetUsersRequest.newBuilder().addIds(seller.id()).addIds(987_654).build());
        assertThat(res.getUsersList()).hasSize(1);
        assertThat(res.getUsers(0).getNickname()).startsWith("테스터");

        // 추천 맥락: 내 취향(온보딩) + 내가 보낸 대기 요청의 상대 물건
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/me/preferences")
                .header("Authorization", buyer.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"wantedCategories\":[\"BOOK\"],\"keywords\":[\"해리포터\"]}")).andExpect(status().isOk());
        long target = TestSupport.createItem(seller, "gRPC 대상 책", "BOOK", 10_000);
        long mine = TestSupport.createItem(buyer, "gRPC 내 책", "BOOK", 10_000);
        String created = mvc.perform(post("/api/exchanges").header("Authorization", buyer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetItemId\":%d,\"offeredItemId\":%d,\"extraAmount\":3000,\"extraPayer\":\"ME\"}"
                                .formatted(target, mine)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long exchangeId = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

        var ctx = authed.getRecommendContext(RecommendContextRequest.newBuilder().setUserId(buyer.id())
                .addOwnerIds(seller.id()).setInvolvingItemId(mine).build());
        assertThat(ctx.getUserFound()).isTrue();
        assertThat(ctx.getKeywordsList()).contains("해리포터");
        assertThat(ctx.getMyWeights().getWeightsMap()).containsKey("BOOK");
        assertThat(ctx.getRequestedTargetItemIdsList()).contains(target);
        assertThat(ctx.getInvolvingItemStatusMap()).containsEntry(target, "REQUESTED");

        // 교환 조건(결제 서버가 안전결제 전에 확인)
        var ex = ExchangeServiceGrpc.newBlockingStub(channel())
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(token()))
                .getExchange(GetExchangeRequest.newBuilder().setExchangeId(exchangeId).build());
        assertThat(ex.getFound()).isTrue();
        assertThat(ex.getExtraAmount()).isEqualTo(3000);
        assertThat(ex.getPayerId()).isEqualTo(buyer.id());
        assertThat(ex.getPayeeId()).isEqualTo(seller.id());
        assertThat(Map.of("status", ex.getStatus())).containsEntry("status", "REQUESTED");
    }
}
