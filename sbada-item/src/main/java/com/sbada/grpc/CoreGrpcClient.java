package com.sbada.grpc;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sbada.common.Category;
import com.sbada.grpc.user.v1.CategoryWeights;
import com.sbada.grpc.user.v1.GetUsersRequest;
import com.sbada.grpc.user.v1.RecommendContextRequest;
import com.sbada.grpc.user.v1.UserServiceGrpc;

import io.grpc.StatusRuntimeException;

/**
 * 메인 서버(회원 서비스)를 부르는 배달부. 호출마다 3초 제한.
 * 메인 서버가 꺼져 있어도 물품 서버는 계속 돈다: 회원 조회는 빈 목록, 추천 맥락은 '취향 없음'으로(점수만 조금 덜 정확)
 */
@Component
public class CoreGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(CoreGrpcClient.class);
    private static final long DEADLINE_MS = 3000;

    private final UserServiceGrpc.UserServiceBlockingStub users;

    public CoreGrpcClient(UserServiceGrpc.UserServiceBlockingStub users) {
        this.users = users;
    }

    public record UserInfo(long id, String nickname, String region, String secondRegion) {
    }

    /**
     * 추천 점수에 필요한 회원 쪽 정보.
     * requested: 내가 보낸 대기 중 요청의 상대 물건, involving: 기준 물건과 얽힌 대기 요청의 상대 물건 → 상태
     */
    public record RecommendContext(List<String> keywords, Map<Category, Double> myWeights,
                                   Map<Long, Map<Category, Double>> ownerWeights, List<Long> requested,
                                   Map<Long, String> involving, Integer priceMin, Integer priceMax) {
        public static RecommendContext empty() {
            return new RecommendContext(List.of(), Map.of(), Map.of(), List.of(), Map.of(), null, null);
        }
    }

    public List<UserInfo> getUsers(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        try {
            return users.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getUsers(GetUsersRequest.newBuilder().addAllIds(ids).build())
                    .getUsersList().stream()
                    .map(u -> new UserInfo(u.getId(), u.getNickname(), u.getRegion(), u.getSecondRegion()))
                    .toList();
        } catch (StatusRuntimeException e) {
            log.warn("회원 서비스 호출 실패(GetUsers {}명): {}", ids.size(), e.getStatus().getCode());
            return List.of();
        }
    }

    public RecommendContext recommendContext(long userId, Collection<Long> ownerIds, long involvingItemId) {
        try {
            var r = users.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getRecommendContext(RecommendContextRequest.newBuilder().setUserId(userId)
                            .addAllOwnerIds(ownerIds).setInvolvingItemId(involvingItemId).build());
            Map<Long, Map<Category, Double>> owners = new HashMap<>();
            r.getOwnerWeightsMap().forEach((id, w) -> owners.put(id, weights(w)));
            return new RecommendContext(List.copyOf(r.getKeywordsList()), weights(r.getMyWeights()), owners,
                    List.copyOf(r.getRequestedTargetItemIdsList()), Map.copyOf(r.getInvolvingItemStatusMap()),
                    r.hasPriceMin() ? r.getPriceMin() : null, r.hasPriceMax() ? r.getPriceMax() : null);
        } catch (StatusRuntimeException e) {
            log.warn("회원 서비스 호출 실패(추천 맥락): {} — 취향 없이 점수를 매겨요", e.getStatus().getCode());
            return RecommendContext.empty();
        }
    }

    private static Map<Category, Double> weights(CategoryWeights w) {
        Map<Category, Double> out = new HashMap<>();
        w.getWeightsMap().forEach((code, v) -> {
            Category c = Category.parseLoose(code);
            if (c != null) {
                out.put(c, v);
            }
        });
        return out;
    }
}
