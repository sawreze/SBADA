package com.sbada.grpc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.events.EventPublisher;
import com.sbada.events.Topics;
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
import com.sbada.grpc.item.v1.ReopenTradeRequest;
import com.sbada.grpc.item.v1.ReopenTradeResponse;
import com.sbada.image.ItemImage;
import com.sbada.image.ItemImageRepository;
import com.sbada.item.Item;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemStatus;

import io.grpc.stub.StreamObserver;

/**
 * 배달부(gRPC) 물품 서비스 — 메인 서버(교환 · 채팅 · 프로필)가 부른다. 포트 9099, 내부 토큰 필수(GrpcConfig).
 *  GetItems      : 물건 요약 여러 개(교환 카드 · 채팅방 위쪽)
 *  CompleteTrade : 교환 성사 → 두 물건을 한 트랜잭션에서 TRADED 로. 하나라도 안 되면 아무것도 안 바꾸고 ok=false
 *  CountItems    : 상태 화면용 물건 수
 *  GetImage      : 프로필 사진으로 고른 사진이 내 것인지 확인
 *  ReopenTrade   : 거래 취소(운송장 7일 만료 등) → 거래 완료였던 물건을 다시 교환 가능으로(2026-11-25)
 */
@GrpcService
public class ItemGrpcService extends ItemServiceGrpc.ItemServiceImplBase {

    private final ItemRepository items;
    private final ItemImageRepository images;
    private final EventPublisher events;
    private final TransactionTemplate tx;

    public ItemGrpcService(ItemRepository items, ItemImageRepository images, EventPublisher events,
                           TransactionTemplate tx) {
        this.items = items;
        this.images = images;
        this.events = events;
        this.tx = tx;
    }

    @Override
    public void getItems(GetItemsRequest request, StreamObserver<GetItemsResponse> out) {
        GetItemsResponse res = tx.execute(s -> {
            GetItemsResponse.Builder b = GetItemsResponse.newBuilder();
            for (Item i : items.findAllById(new LinkedHashSet<>(request.getIdsList()))) {
                b.addItems(toInfo(i));
            }
            return b.build();
        });
        out.onNext(res);
        out.onCompleted();
    }

    static ItemInfo toInfo(Item i) {
        String image = i.getImages().isEmpty() ? "" : i.getImages().get(0).url();
        return ItemInfo.newBuilder()
                .setId(i.getId())
                .setOwnerId(i.getOwner().getId())
                .setTitle(i.getTitle())
                .setPrice(i.getPrice())
                .setStatus(i.getStatus().name())
                .setCategory(i.getCategory().name())
                .setCategoryLabel(i.getCategory().label())
                .setImageUrl(image)
                .setCondition(i.getItemCondition().name())
                .build();
    }

    @Override
    public void completeTrade(CompleteTradeRequest request, StreamObserver<CompleteTradeResponse> out) {
        CompleteTradeResponse res = tx.execute(s -> {
            List<Item> found = new ArrayList<>(items.findAllById(new LinkedHashSet<>(request.getItemIdsList())));
            if (found.size() != new LinkedHashSet<>(request.getItemIdsList()).size()) {
                return fail("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다.");
            }
            for (Item i : found) {
                if (i.getStatus() != ItemStatus.AVAILABLE) {
                    return fail("ITEM_NOT_AVAILABLE", "'" + i.getTitle() + "'은(는) 이미 교환되었거나 내려간 물건입니다.");
                }
            }
            for (Item i : found) {
                i.markTraded();
                events.publish(Topics.ITEM, i.getId(), "ITEM_STATUS_CHANGED", Map.of("itemId", i.getId(),
                        "ownerId", i.getOwner().getId(), "title", i.getTitle(), "status", ItemStatus.TRADED.name(),
                        "exchangeId", request.getExchangeId(),
                        "reason", "물건이 다른 교환으로 거래 완료되어 자동 취소되었어요."));
            }
            return CompleteTradeResponse.newBuilder().setOk(true).build();
        });
        out.onNext(res);
        out.onCompleted();
    }

    @Override
    public void reopenTrade(ReopenTradeRequest request, StreamObserver<ReopenTradeResponse> out) {
        Integer n = tx.execute(s -> {
            int count = 0;
            for (Item i : items.findAllById(new LinkedHashSet<>(request.getItemIdsList()))) {
                if (i.reopenAfterCanceledTrade()) {
                    count++;
                    events.publish(Topics.ITEM, i.getId(), "ITEM_STATUS_CHANGED", Map.of("itemId", i.getId(),
                            "ownerId", i.getOwner().getId(), "title", i.getTitle(), "status", ItemStatus.AVAILABLE.name(),
                            "exchangeId", request.getExchangeId()));
                }
            }
            return count;
        });
        out.onNext(ReopenTradeResponse.newBuilder().setReopened(n == null ? 0 : n).build());
        out.onCompleted();
    }

    private static CompleteTradeResponse fail(String code, String message) {
        return CompleteTradeResponse.newBuilder().setOk(false).setCode(code).setMessage(message).build();
    }

    @Override
    public void countItems(CountItemsRequest request, StreamObserver<CountItemsResponse> out) {
        out.onNext(CountItemsResponse.newBuilder()
                .setTotal(items.count())
                .setAvailable(items.countInArea(ItemStatus.AVAILABLE, null))
                .build());
        out.onCompleted();
    }

    @Override
    public void getImage(GetImageRequest request, StreamObserver<ImageInfo> out) {
        ImageInfo res = tx.execute(s -> images.findById(request.getImageId())
                .map((ItemImage i) -> ImageInfo.newBuilder().setFound(true).setId(i.getId())
                        .setUploaderId(i.getUploader().getId()).setUrl(i.url())
                        .setItemId(i.getItem() == null ? 0 : i.getItem().getId()).build())
                .orElse(ImageInfo.newBuilder().setFound(false).build()));
        out.onNext(res);
        out.onCompleted();
    }
}
