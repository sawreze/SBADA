package com.sbada.grpc;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;

import com.sbada.grpc.user.v1.UserServiceGrpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * 배달부(gRPC) 설정 (2026-11-25 MSA 2단계)
 *  - 서버(:9099): 모든 호출의 메타데이터 x-internal-token 을 확인한다. 없거나 틀리면 UNAUTHENTICATED
 *    (앱 · 외부에서는 부를 수 없고 같은 비밀 파일을 가진 서버끼리만)
 *  - 클라이언트: 메인 서버(회원 서비스 :9097)로 가는 채널. 호출마다 토큰을 붙인다
 *    주소는 sbada.grpc.core-target (기본 127.0.0.1:9097, 테스트에서는 in-process:이름)
 */
@Configuration
public class GrpcConfig {

    static final Metadata.Key<String> TOKEN = Metadata.Key.of(InternalToken.GRPC_KEY, Metadata.ASCII_STRING_MARSHALLER);

    @Bean
    @GlobalServerInterceptor
    ServerInterceptor internalTokenCheck(InternalToken token) {
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                              ServerCallHandler<Q, R> next) {
                String service = call.getMethodDescriptor().getServiceName();
                if (service != null && service.startsWith("grpc.")) { // 상태 확인(grpc.health) · 리플렉션은 통과
                    return next.startCall(call, headers);
                }
                if (!token.matches(headers.get(TOKEN))) {
                    call.close(Status.UNAUTHENTICATED.withDescription("INTERNAL_AUTH_FAILED|내부 서비스 인증에 실패했습니다."),
                            new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                return next.startCall(call, headers);
            }
        };
    }

    /** 나가는 호출에 내부 토큰을 붙인다 */
    static ClientInterceptor tokenAttacher(InternalToken token) {
        return new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<R> listener, Metadata headers) {
                        headers.put(TOKEN, token.value());
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    @Bean
    UserServiceGrpc.UserServiceBlockingStub userServiceStub(GrpcChannelFactory channels, InternalToken token,
                                                            @Value("${sbada.grpc.core-target:127.0.0.1:9097}") String target) {
        return UserServiceGrpc.newBlockingStub(channels.createChannel(target,
                ChannelBuilderOptions.defaults().withInterceptors(List.of(tokenAttacher(token)))));
    }
}
