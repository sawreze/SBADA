package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.sbada.auth.JwtService;
import com.sbada.common.Json;
import com.sbada.events.EventConsumers;
import com.sbada.events.OutboxEvent;
import com.sbada.events.OutboxRepository;
import com.sbada.events.Topics;
import com.sbada.grpc.InternalToken;
import com.sbada.grpc.item.v1.CompleteTradeRequest;
import com.sbada.grpc.item.v1.GetImageRequest;
import com.sbada.grpc.item.v1.GetItemsRequest;
import com.sbada.grpc.item.v1.ItemServiceGrpc;
import com.sbada.item.ItemRepository;
import com.sbada.legacy.LegacyImporter;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;

/**
 * 물품 서버(MSA 2단계) 통합 테스트 — 메인 서버 · Kafka 없이:
 *  REST 등록 → REST · GraphQL 목록(판매자 정보는 가짜 회원 서비스 gRPC) → 찜 → 아웃박스 이벤트
 *  배달부(gRPC) GetItems · CompleteTrade · GetImage · 토큰 확인, 허브 이벤트 소비(멱등), 옛 데이터 옮기기
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sbada-item-test;DB_CLOSE_DELAY=-1",
        "sbada.ai.provider=fallback",
        "sbada.ai.google.api-key=",
        "sbada.jwt.secret-file=./build/test-data/.jwt-secret",
        "sbada.internal.token-file=./build/test-data/.internal-token",
        "sbada.upload.dir=./build/test-data/uploads",
        "sbada.item.import.enabled=false",
        "sbada.events.relay-enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.grpc.server.port=0",
        "spring.grpc.server.inprocess.name=item-test",
        "sbada.grpc.core-target=in-process:item-test"
})
@AutoConfigureMockMvc
@Import(ItemTestConfig.class)
class ItemServiceApiTest {

    private static final AtomicLong IDS = new AtomicLong(9000);
    private static ManagedChannel channel;

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtService jwt;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    EventConsumers consumers;
    @Autowired
    InternalToken internal;
    @Autowired
    LegacyImporter importer;
    @Autowired
    ItemRepository items;

    @AfterAll
    static void closeChannel() {
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private long newUser(String nickname, String region) {
        long id = IDS.incrementAndGet();
        ItemTestConfig.USERS.put(id, new String[] {nickname, region});
        return id;
    }

    private String bearer(long userId) {
        return "Bearer " + jwt.issue(userId);
    }

    private static byte[] png(Color color) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private long uploadImage(long user) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", png(Color.ORANGE));
        String body = mvc.perform(multipart("/api/images").file(file).header("Authorization", bearer(user)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createItem(long user, String title, String category) throws Exception {
        long imageId = uploadImage(user);
        String analysis = mvc.perform(post("/api/ai/analyze").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageId\":%d,\"category\":\"%s\",\"condition\":\"GOOD\"}".formatted(imageId, category)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long analysisId = ((Number) JsonPath.read(analysis, "$.analysisId")).longValue();
        String body = mvc.perform(post("/api/items").header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"물품 서버 테스트","category":"%s","condition":"GOOD",
                                 "imageIds":[%d],"analysisId":%d,"wishCategories":[]}
                                """.formatted(title, category, imageId, analysisId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions graphql(String query, Long user) throws Exception {
        var req = post("/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(Json.write(Map.of("query", query)));
        if (user != null) {
            req = req.header("Authorization", bearer(user));
        }
        return mvc.perform(req).andExpect(status().isOk());
    }

    private ItemServiceGrpc.ItemServiceBlockingStub stub(boolean withToken) {
        if (channel == null) {
            channel = InProcessChannelBuilder.forName("item-test").directExecutor().build();
        }
        var stub = ItemServiceGrpc.newBlockingStub(channel);
        if (!withToken) {
            return stub;
        }
        Metadata md = new Metadata();
        md.put(Metadata.Key.of(InternalToken.GRPC_KEY, Metadata.ASCII_STRING_MARSHALLER), internal.value());
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));
    }

    @Test
    void registerThenListByRestAndGraphqlWithLikes() throws Exception {
        long seller = newUser("판매왕", "서울 마포구 망원동");
        long fan = newUser("구경꾼", "서울 용산구");
        long itemId = createItem(seller, "물품서버 키보드", "DIGITAL");

        // REST 목록: 판매자 닉네임 · 동네는 회원 서비스(gRPC)에서 가져와 판매자 사본에 저장된 것
        mvc.perform(get("/api/items").param("area", "서울 마포구"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == %d)].owner.nickname".formatted(itemId)).value("판매왕"));

        // GraphQL: 필요한 칸만. 로그인 안 하면 likedByMe = false
        String q = "{ items(area: \"서울 마포구\", limit: 50) { id title price owner { nickname region } likedByMe } }";
        graphql(q, null)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.items[?(@.id == %d)].title".formatted(itemId)).value("물품서버 키보드"))
                .andExpect(jsonPath("$.data.items[?(@.id == %d)].owner.region".formatted(itemId)).value("서울 마포구 망원동"))
                .andExpect(jsonPath("$.data.items[?(@.id == %d)].likedByMe".formatted(itemId)).value(false))
                .andExpect(jsonPath("$.data.items[0].description").doesNotExist()); // 안 물어본 칸은 안 온다

        // 찜 → GraphQL likedByMe = true(그 사람에게만), 주인에게 알림 이벤트(sbada.notifications)
        mvc.perform(post("/api/items/" + itemId + "/like").header("Authorization", bearer(fan)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.liked").value(true));
        graphql(q, fan).andExpect(jsonPath("$.data.items[?(@.id == %d)].likedByMe".formatted(itemId)).value(true));
        graphql(q, seller).andExpect(jsonPath("$.data.items[?(@.id == %d)].likedByMe".formatted(itemId)).value(false));
        graphql("{ myLikes { id } itemCount(area: \"서울 마포구\") }", fan)
                .andExpect(jsonPath("$.data.myLikes[0].id").value((int) itemId));
        graphql("{ myItems { id } }", null)
                .andExpect(jsonPath("$.errors[0].extensions.code").value("AUTH_REQUIRED"));

        List<OutboxEvent> events = outbox.findAll();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo(Topics.ITEM);
            assertThat(e.getEventType()).isEqualTo("ITEM_CREATED");
            assertThat(e.getPayload()).contains("\"itemId\":" + itemId);
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo(Topics.NOTIFICATIONS);
            assertThat(e.getPayload()).contains("ITEM_LIKED").contains("\"userId\":" + seller);
        });
    }

    @Test
    void grpcGetItemsCompleteTradeAndImage() throws Exception {
        long a = newUser("교환A", "부산 해운대구");
        long b = newUser("교환B", "부산 수영구");
        long itemA = createItem(a, "gRPC 텐트", "SPORTS");
        long itemB = createItem(b, "gRPC 랜턴", "SPORTS");

        // 토큰 없이 부르면 UNAUTHENTICATED
        assertThatThrownBy(() -> stub(false).getItems(GetItemsRequest.newBuilder().addIds(itemA).build()))
                .isInstanceOf(StatusRuntimeException.class).hasMessageContaining("UNAUTHENTICATED");

        var res = stub(true).getItems(GetItemsRequest.newBuilder().addIds(itemA).addIds(itemB).addIds(999_999).build());
        assertThat(res.getItemsList()).hasSize(2);
        assertThat(res.getItems(0).getTitle()).isEqualTo("gRPC 텐트");
        assertThat(res.getItems(0).getOwnerId()).isEqualTo(a);
        assertThat(res.getItems(0).getImageUrl()).startsWith("/api/images/");

        // 교환 성사: 두 물건 모두 TRADED. 다시 하면 ok=false(이미 교환됨) — 아무것도 바뀌지 않는다
        var ok = stub(true).completeTrade(CompleteTradeRequest.newBuilder().setExchangeId(77)
                .addItemIds(itemA).addItemIds(itemB).build());
        assertThat(ok.getOk()).isTrue();
        var again = stub(true).completeTrade(CompleteTradeRequest.newBuilder().setExchangeId(78)
                .addItemIds(itemA).build());
        assertThat(again.getOk()).isFalse();
        assertThat(again.getCode()).isEqualTo("ITEM_NOT_AVAILABLE");
        mvc.perform(get("/api/items/" + itemA)).andExpect(jsonPath("$.status").value("TRADED"));
        assertThat(outbox.findAll()).anySatisfy(e -> assertThat(e.getPayload())
                .contains("ITEM_STATUS_CHANGED").contains("\"status\":\"TRADED\"").contains("\"itemId\":" + itemB));

        // 사진 확인(프로필 사진으로 고를 때)
        long photo = uploadImage(a);
        var img = stub(true).getImage(GetImageRequest.newBuilder().setImageId(photo).build());
        assertThat(img.getFound()).isTrue();
        assertThat(img.getUploaderId()).isEqualTo(a);
        assertThat(img.getItemId()).isZero();
        assertThat(stub(true).getImage(GetImageRequest.newBuilder().setImageId(888_888).build()).getFound()).isFalse();
    }

    @Test
    void consumesExchangeAndUserEventsOnce() throws Exception {
        long owner = newUser("이벤트주인", "대구 중구");
        long itemId = createItem(owner, "이벤트 자전거", "SPORTS");

        String requested = """
                {"eventId":"ev-req-%d","type":"EXCHANGE_REQUESTED","exchangeId":5,"offeredItemId":1,"targetItemId":%d}
                """.formatted(itemId, itemId);
        assertThat(consumers.handle(requested)).isTrue();
        assertThat(consumers.handle(requested)).isFalse(); // 같은 이벤트 두 번 → 한 번만
        mvc.perform(get("/api/items/" + itemId)).andExpect(jsonPath("$.requestCount").value(1));

        String renamed = """
                {"eventId":"ev-user-%d","type":"USER_UPDATED","userId":%d,"nickname":"새이름","region":"대구 수성구"}
                """.formatted(owner, owner);
        assertThat(consumers.handle(renamed)).isTrue();
        mvc.perform(get("/api/items/" + itemId))
                .andExpect(jsonPath("$.owner.nickname").value("새이름"))
                .andExpect(jsonPath("$.owner.region").value("대구 수성구"));
    }

    @Test
    void legacyImportKeepsIdsAndContinuesNumbering() throws Exception {
        String json = """
                {"sellers":[{"ID":70001,"NICKNAME":"옛판매자","REGION":"광주 북구","SECOND_REGION":null,
                             "UPDATED_AT":"2026-10-01T00:00:00Z"}],
                 "items":[{"ID":70001,"OWNER_ID":70001,"TITLE":"옛날 물건","DESCRIPTION":"메인 DB 에서 옮김",
                           "CATEGORY":"BOOK","ITEM_CONDITION":"GOOD","STATUS":"AVAILABLE","AI_PRICE":12000,
                           "PRICE_MIN":10000,"PRICE_MAX":14000,"AI_CONFIDENCE":0.7,"AI_REASON":"옮김","AI_PROVIDER":"fallback",
                           "VIEW_COUNT":3,"REQUEST_COUNT":1,"VERSION":0,"CREATED_AT":"2026-10-01T09:00:00+09:00",
                           "UPDATED_AT":"2026-10-01T09:00:00+09:00"}],
                 "item_wish_categories":[{"ITEM_ID":70001,"CATEGORY":"HOBBY"}],
                 "item_images":[],"ai_analyses":[],"item_likes":[]}
                """;
        assertThat(importer.importJson(json)).isEqualTo(3);
        mvc.perform(get("/api/items/70001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("옛날 물건"))
                .andExpect(jsonPath("$.owner.nickname").value("옛판매자"))
                .andExpect(jsonPath("$.wishCategories[0]").value("HOBBY"));
        // 옮긴 뒤 새 물건 번호는 그 다음부터
        long next = createItem(newUser("새판매자", "광주 남구"), "새 물건", "BOOK");
        assertThat(next).isGreaterThan(70001);
        assertThat(items.findById(70001L)).isPresent();
    }
}
