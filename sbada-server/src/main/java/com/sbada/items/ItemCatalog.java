package com.sbada.items;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;
import com.sbada.grpc.item.v1.CompleteTradeRequest;
import com.sbada.grpc.item.v1.CountItemsRequest;
import com.sbada.grpc.item.v1.GetImageRequest;
import com.sbada.grpc.item.v1.GetItemsRequest;
import com.sbada.grpc.item.v1.ItemInfo;
import com.sbada.grpc.item.v1.ItemServiceGrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 물품 서버를 부르는 배달부(gRPC, :9099) (2026-11-25 MSA 2단계). 호출마다 3초 제한.
 *  get(ids)          : 교환 · 채팅 화면용 요약(여러 개 한 번에). 물품 서버가 꺼져 있으면 빈 Map → 화면은 '불러오지 못했어요'
 *  require(id)       : 꼭 있어야 할 때(교환 요청 · 채팅 시작). 없거나 내려간 물건이면 404, 서버가 꺼져 있으면 503
 *  completeTrade(..) : 교환 성사 → 두 물건을 거래 완료로(물품 서버가 한 트랜잭션에서). 안 되면 409
 */
@Component
public class ItemCatalog {

    private static final Logger log = LoggerFactory.getLogger(ItemCatalog.class);
    private static final long DEADLINE_MS = 3000;

    private final ItemServiceGrpc.ItemServiceBlockingStub stub;

    public ItemCatalog(ItemServiceGrpc.ItemServiceBlockingStub stub) {
        this.stub = stub;
    }

    private ItemServiceGrpc.ItemServiceBlockingStub call() {
        return stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS);
    }

    public Map<Long, ItemRef> get(Collection<Long> ids) {
        Map<Long, ItemRef> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        try {
            for (ItemInfo i : call().getItems(GetItemsRequest.newBuilder().addAllIds(new LinkedHashSet<>(ids)).build())
                    .getItemsList()) {
                out.put(i.getId(), toRef(i));
            }
        } catch (StatusRuntimeException e) {
            log.warn("물품 서비스 호출 실패(GetItems {}개): {}", ids.size(), e.getStatus().getCode());
        }
        return out;
    }

    /** 숨긴(HIDDEN) 물건은 없는 것으로 본다(예전 규칙 그대로) */
    public ItemRef require(long id) {
        ItemRef ref;
        try {
            List<ItemInfo> found = call().getItems(GetItemsRequest.newBuilder().addIds(id).build()).getItemsList();
            ref = found.isEmpty() ? null : toRef(found.get(0));
        } catch (StatusRuntimeException e) {
            throw down(e);
        }
        if (ref == null || "HIDDEN".equals(ref.status())) {
            throw ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다.");
        }
        return ref;
    }

    /** 교환 성사. 두 물건 중 하나라도 이미 교환 · 내려감이면 409(아무것도 바뀌지 않음) */
    public void completeTrade(long exchangeId, long... itemIds) {
        CompleteTradeRequest.Builder b = CompleteTradeRequest.newBuilder().setExchangeId(exchangeId);
        for (long id : itemIds) {
            b.addItemIds(id);
        }
        var res = callOrDown(() -> call().completeTrade(b.build()));
        if (!res.getOk()) {
            throw ApiException.conflict(res.getCode().isBlank() ? "ITEM_NOT_AVAILABLE" : res.getCode(), res.getMessage());
        }
    }

    /** 2026-11-25 거래 취소(운송장 7일 만료 등): 이 교환으로 거래 완료였던 물건을 다시 교환 가능으로. 다시 연 물건 수 */
    public int reopenTrade(long exchangeId, long... itemIds) {
        com.sbada.grpc.item.v1.ReopenTradeRequest.Builder b =
                com.sbada.grpc.item.v1.ReopenTradeRequest.newBuilder().setExchangeId(exchangeId);
        for (long id : itemIds) {
            b.addItemIds(id);
        }
        return callOrDown(() -> call().reopenTrade(b.build())).getReopened();
    }

    /** 상태 화면용 물건 수(-1 = 물품 서버 꺼짐) */
    public long count() {
        try {
            return call().countItems(CountItemsRequest.getDefaultInstance()).getTotal();
        } catch (StatusRuntimeException e) {
            return -1;
        }
    }

    /** 사진의 올린 사람(프로필 사진 확인). 없으면 404 */
    public long imageUploader(long imageId) {
        var img = callOrDown(() -> call().getImage(GetImageRequest.newBuilder().setImageId(imageId).build()));
        if (!img.getFound()) {
            throw ApiException.notFound("IMAGE_NOT_FOUND", "사진을 찾을 수 없습니다.");
        }
        return img.getUploaderId();
    }

    private static ItemRef toRef(ItemInfo i) {
        return new ItemRef(i.getId(), i.getOwnerId(), i.getTitle(), i.getPrice(), i.getStatus(), i.getCategory(),
                i.getCategoryLabel(), i.getImageUrl().isEmpty() ? null : i.getImageUrl(),
                i.getCondition().isEmpty() ? null : i.getCondition());
    }

    private static <T> T callOrDown(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (StatusRuntimeException e) {
            throw down(e);
        }
    }

    private static ApiException down(StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
            return new ApiException(503, "ITEM_SERVICE_AUTH", "물품 서비스 내부 인증에 실패했어요(내부 토큰 파일 확인).");
        }
        return new ApiException(503, "ITEM_SERVICE_DOWN",
                "물품 서버가 꺼져 있어요. 물품 서버를 켠 뒤 다시 시도해 주세요. (채팅 · 알림은 그대로 쓸 수 있어요)");
    }
}
