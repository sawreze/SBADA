package com.sbada;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;

import com.sbada.grpc.user.v1.CategoryWeights;
import com.sbada.grpc.user.v1.GetUsersRequest;
import com.sbada.grpc.user.v1.GetUsersResponse;
import com.sbada.grpc.user.v1.RecommendContext;
import com.sbada.grpc.user.v1.RecommendContextRequest;
import com.sbada.grpc.user.v1.User;
import com.sbada.grpc.user.v1.UserServiceGrpc;

import io.grpc.stub.StreamObserver;

/**
 * 테스트용 가짜 회원 서비스(메인 서버 대신). 물품 서버가 자기 in-process gRPC 서버에 이것도 함께 올리고,
 * 클라이언트(core 채널)는 in-process:item-test 로 이것을 부른다 → 메인 서버 없이 배달부 호출까지 시험한다.
 */
@org.springframework.context.annotation.Configuration
public class ItemTestConfig {

    /** 회원 번호 → {닉네임, 동네} */
    public static final Map<Long, String[]> USERS = new ConcurrentHashMap<>();
    /** 회원 번호 → 카테고리 취향 */
    public static final Map<Long, Map<String, Double>> WEIGHTS = new ConcurrentHashMap<>();

    @Bean
    UserServiceGrpc.UserServiceImplBase fakeUserService() {
        return new UserServiceGrpc.UserServiceImplBase() {
            @Override
            public void getUsers(GetUsersRequest request, StreamObserver<GetUsersResponse> out) {
                GetUsersResponse.Builder b = GetUsersResponse.newBuilder();
                for (long id : request.getIdsList()) {
                    String[] u = USERS.get(id);
                    if (u != null) {
                        b.addUsers(User.newBuilder().setId(id).setNickname(u[0]).setRegion(u[1]).build());
                    }
                }
                out.onNext(b.build());
                out.onCompleted();
            }

            @Override
            public void getRecommendContext(RecommendContextRequest request, StreamObserver<RecommendContext> out) {
                RecommendContext.Builder b = RecommendContext.newBuilder()
                        .setUserFound(USERS.containsKey(request.getUserId()))
                        .setMyWeights(CategoryWeights.newBuilder()
                                .putAllWeights(WEIGHTS.getOrDefault(request.getUserId(), Map.of())));
                for (long owner : request.getOwnerIdsList()) {
                    b.putOwnerWeights(owner, CategoryWeights.newBuilder()
                            .putAllWeights(WEIGHTS.getOrDefault(owner, Map.of())).build());
                }
                out.onNext(b.build());
                out.onCompleted();
            }
        };
    }
}
