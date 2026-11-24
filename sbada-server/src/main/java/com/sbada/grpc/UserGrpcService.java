package com.sbada.grpc;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.Category;
import com.sbada.exchange.ExchangeRepository;
import com.sbada.exchange.ExchangeRequest;
import com.sbada.exchange.ExchangeStatus;
import com.sbada.grpc.user.v1.CategoryWeights;
import com.sbada.grpc.user.v1.GetUsersRequest;
import com.sbada.grpc.user.v1.GetUsersResponse;
import com.sbada.grpc.user.v1.RecommendContext;
import com.sbada.grpc.user.v1.RecommendContextRequest;
import com.sbada.grpc.user.v1.User;
import com.sbada.grpc.user.v1.UserServiceGrpc;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;
import com.sbada.user.PreferenceService;

import io.grpc.stub.StreamObserver;

/**
 * 배달부(gRPC) 회원 서비스 — 물품 서버가 부른다 (2026-11-25 MSA 2단계, :9097, 내부 토큰 필수)
 *  GetUsers            : 판매자 닉네임 · 동네(물품 서버의 판매자 사본을 채운다). 비밀번호 등은 보내지 않는다
 *  GetRecommendContext : 추천 점수에 필요한 회원 쪽 정보를 한 번에 — 내 키워드 · 취향 · 가격대,
 *                        후보 주인들의 취향, 내가 보낸 대기 중 요청, 기준 물건과 얽힌 대기 요청
 */
@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final AppUserRepository users;
    private final PreferenceService preferences;
    private final ExchangeRepository exchanges;
    private final TransactionTemplate tx;

    public UserGrpcService(AppUserRepository users, PreferenceService preferences, ExchangeRepository exchanges,
                           TransactionTemplate tx) {
        this.users = users;
        this.preferences = preferences;
        this.exchanges = exchanges;
        this.tx = tx;
    }

    @Override
    public void getUsers(GetUsersRequest request, StreamObserver<GetUsersResponse> out) {
        GetUsersResponse res = tx.execute(s -> {
            GetUsersResponse.Builder b = GetUsersResponse.newBuilder();
            for (AppUser u : users.findAllById(new HashSet<>(request.getIdsList()))) {
                b.addUsers(User.newBuilder().setId(u.getId()).setNickname(u.getNickname())
                        .setRegion(u.getRegion() == null ? "" : u.getRegion())
                        .setSecondRegion(u.getSecondRegion() == null ? "" : u.getSecondRegion())
                        .setProfileImageId(u.getProfileImageId() == null ? 0 : u.getProfileImageId()).build());
            }
            return b.build();
        });
        out.onNext(res);
        out.onCompleted();
    }

    @Override
    public void getRecommendContext(RecommendContextRequest request, StreamObserver<RecommendContext> out) {
        RecommendContext res = tx.execute(s -> {
            long userId = request.getUserId();
            RecommendContext.Builder b = RecommendContext.newBuilder();
            AppUser me = users.findById(userId).orElse(null);
            b.setUserFound(me != null);
            if (me != null) {
                b.addAllKeywords(me.getKeywords());
                if (me.getPriceMin() != null) {
                    b.setPriceMin(me.getPriceMin());
                }
                if (me.getPriceMax() != null) {
                    b.setPriceMax(me.getPriceMax());
                }
            }
            b.setMyWeights(weights(preferences.weightsOf(userId)));
            Set<Long> owners = new HashSet<>(request.getOwnerIdsList());
            if (!owners.isEmpty()) {
                preferences.weightsOfUsers(owners).forEach((id, w) -> b.putOwnerWeights(id, weights(w)));
            }
            for (ExchangeRequest e : exchanges.findSentWithStatus(userId, ExchangeStatus.REQUESTED)) {
                b.addRequestedTargetItemIds(e.getTargetItemId());
            }
            long base = request.getInvolvingItemId();
            if (base != 0) {
                for (ExchangeRequest e : exchanges.findInvolving(List.of(base), ExchangeStatus.REQUESTED)) {
                    long other = e.getOfferedItemId() == base ? e.getTargetItemId() : e.getOfferedItemId();
                    b.putInvolvingItemStatus(other, e.getStatus().name());
                }
            }
            return b.build();
        });
        out.onNext(res);
        out.onCompleted();
    }

    private static CategoryWeights weights(Map<Category, Double> w) {
        CategoryWeights.Builder b = CategoryWeights.newBuilder();
        w.forEach((c, v) -> b.putWeights(c.name(), v));
        return b.build();
    }
}
