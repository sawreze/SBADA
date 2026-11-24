package com.sbada;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sbada.grpc.item.v1.CompleteTradeRequest;
import com.sbada.grpc.item.v1.CompleteTradeResponse;
import com.sbada.grpc.item.v1.CountItemsRequest;
import com.sbada.grpc.item.v1.CountItemsResponse;
import com.sbada.grpc.item.v1.GetImageRequest;
import com.sbada.grpc.item.v1.GetItemsRequest;
import com.sbada.grpc.item.v1.GetItemsResponse;
import com.sbada.grpc.item.v1.ImageInfo;
import com.sbada.grpc.item.v1.ItemInfo;
import com.sbada.grpc.item.v1.ItemServiceGrpc;
import com.sbada.grpc.payment.v1.EscrowCommand;
import com.sbada.grpc.payment.v1.EscrowResult;
import com.sbada.grpc.payment.v1.EscrowServiceGrpc;

import io.grpc.stub.StreamObserver;

/**
 * 테스트용 가짜 물품 서버 · 결제 서버 (2026-11-25 MSA 2단계)
 * 메인 서버 테스트가 진짜 서버 없이 돌도록, 같은 앱의 in-process gRPC 서버에 가짜 ItemService · EscrowService 를 올린다.
 * (테스트 소스의 @Configuration 도 컴포넌트 스캔에 걸려서 모든 테스트에 자동으로 들어간다)
 *  - ITEMS: 물건 번호 → 정보. TestSupport.createItem 이 여기에 넣는다
 *  - ESCROW_CALLS: 결제 서버로 간 정산 · 환불 호출 기록 ("release 7" · "refund 7 이유")
 */
@Configuration
public class FakeServices {

    public record FakeItem(long id, long ownerId, String title, int price, String category, String condition,
                           String status) {
        FakeItem withStatus(String s) {
            return new FakeItem(id, ownerId, title, price, category, condition, s);
        }
    }

    public static final Map<Long, FakeItem> ITEMS = new ConcurrentHashMap<>();
    public static final Map<Long, Long> IMAGES = new ConcurrentHashMap<>(); // 사진 번호 → 올린 사람
    public static final List<String> ESCROW_CALLS = new CopyOnWriteArrayList<>();
    private static final AtomicLong IDS = new AtomicLong(500_000);

    public static long addItem(long ownerId, String title, String category, String condition, int price) {
        long id = IDS.incrementAndGet();
        ITEMS.put(id, new FakeItem(id, ownerId, title, price, category, condition, "AVAILABLE"));
        return id;
    }

    public static long addImage(long uploaderId) {
        long id = IDS.incrementAndGet();
        IMAGES.put(id, uploaderId);
        return id;
    }

    public static String status(long itemId) {
        FakeItem i = ITEMS.get(itemId);
        return i == null ? null : i.status();
    }

    public static void setStatus(long itemId, String status) {
        ITEMS.computeIfPresent(itemId, (k, v) -> v.withStatus(status));
    }

    @Bean
    ItemServiceGrpc.ItemServiceImplBase fakeItemService() {
        return new ItemServiceGrpc.ItemServiceImplBase() {
            @Override
            public void getItems(GetItemsRequest request, StreamObserver<GetItemsResponse> out) {
                GetItemsResponse.Builder b = GetItemsResponse.newBuilder();
                for (long id : request.getIdsList()) {
                    FakeItem i = ITEMS.get(id);
                    if (i != null) {
                        b.addItems(ItemInfo.newBuilder().setId(i.id()).setOwnerId(i.ownerId()).setTitle(i.title())
                                .setPrice(i.price()).setStatus(i.status()).setCategory(i.category())
                                .setCategoryLabel(i.category()).setImageUrl("/api/images/" + i.id())
                                .setCondition(i.condition()).build());
                    }
                }
                out.onNext(b.build());
                out.onCompleted();
            }

            @Override
            public synchronized void completeTrade(CompleteTradeRequest request,
                                                   StreamObserver<CompleteTradeResponse> out) {
                for (long id : request.getItemIdsList()) {
                    FakeItem i = ITEMS.get(id);
                    if (i == null || !"AVAILABLE".equals(i.status())) {
                        out.onNext(CompleteTradeResponse.newBuilder().setOk(false).setCode("ITEM_NOT_AVAILABLE")
                                .setMessage("이미 교환되었거나 내려간 물건입니다.").build());
                        out.onCompleted();
                        return;
                    }
                }
                request.getItemIdsList().forEach(id -> setStatus(id, "TRADED"));
                out.onNext(CompleteTradeResponse.newBuilder().setOk(true).build());
                out.onCompleted();
            }

            @Override
            public void countItems(CountItemsRequest request, StreamObserver<CountItemsResponse> out) {
                out.onNext(CountItemsResponse.newBuilder().setTotal(ITEMS.size()).build());
                out.onCompleted();
            }

            @Override
            public void getImage(GetImageRequest request, StreamObserver<ImageInfo> out) {
                Long uploader = IMAGES.get(request.getImageId());
                out.onNext(uploader == null ? ImageInfo.newBuilder().setFound(false).build()
                        : ImageInfo.newBuilder().setFound(true).setId(request.getImageId()).setUploaderId(uploader)
                                .setUrl("/api/images/" + request.getImageId()).build());
                out.onCompleted();
            }
        };
    }

    @Bean
    EscrowServiceGrpc.EscrowServiceImplBase fakeEscrowService() {
        return new EscrowServiceGrpc.EscrowServiceImplBase() {
            @Override
            public void release(EscrowCommand request, StreamObserver<EscrowResult> out) {
                ESCROW_CALLS.add("release " + request.getExchangeId());
                out.onNext(EscrowResult.newBuilder().setStatus("RELEASED").build());
                out.onCompleted();
            }

            @Override
            public void refund(EscrowCommand request, StreamObserver<EscrowResult> out) {
                ESCROW_CALLS.add("refund " + request.getExchangeId() + " " + request.getReason());
                out.onNext(EscrowResult.newBuilder().setStatus("REFUNDED").build());
                out.onCompleted();
            }
        };
    }
}
