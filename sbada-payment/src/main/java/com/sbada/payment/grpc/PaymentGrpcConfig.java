package com.sbada.payment.grpc;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;

import com.sbada.grpc.exchange.v1.ExchangeServiceGrpc;
import com.sbada.grpc.user.v1.UserServiceGrpc;
import com.sbada.payment.events.InternalSecret;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * 배달부(gRPC) 설정 — 결제 서버 (2026-11-25 MSA 2단계)
 *  - 서버(:9098): EscrowService(정산 · 환불)를 메인 서버에 제공. 메타데이터 x-internal-token 이 없거나 틀리면 UNAUTHENTICATED
 *  - 클라이언트: 메인 서버(:9097)의 UserService · ExchangeService. 호출마다 토큰을 붙인다(sbada.grpc.core-target)
 */
@Configuration
public class PaymentGrpcConfig {

    static final Metadata.Key<String> TOKEN = Metadata.Key.of(InternalSecret.GRPC_KEY, Metadata.ASCII_STRING_MARSHALLER);

    @Bean
    @GlobalServerInterceptor
    ServerInterceptor internalTokenCheck(InternalSecret secret) {
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                              ServerCallHandler<Q, R> next) {
                String service = call.getMethodDescriptor().getServiceName();
                if (service != null && service.startsWith("grpc.")) {
                    return next.startCall(call, headers);
                }
                if (!secret.matches(headers.get(TOKEN))) {
                    call.close(Status.UNAUTHENTICATED.withDescription("INTERNAL_AUTH_FAILED|내부 서비스 인증에 실패했습니다."),
                            new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                return next.startCall(call, headers);
            }
        };
    }

    static ClientInterceptor tokenAttacher(InternalSecret secret) {
        return new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<R> listener, Metadata headers) {
                        headers.put(TOKEN, secret.value());
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    @Bean
    ManagedChannel coreChannel(GrpcChannelFactory channels, InternalSecret secret,
                               @Value("${sbada.grpc.core-target:127.0.0.1:9097}") String target) {
        return channels.createChannel(target,
                ChannelBuilderOptions.defaults().withInterceptors(List.of(tokenAttacher(secret))));
    }

    @Bean
    UserServiceGrpc.UserServiceBlockingStub userServiceStub(ManagedChannel coreChannel) {
        return UserServiceGrpc.newBlockingStub(coreChannel);
    }

    @Bean
    ExchangeServiceGrpc.ExchangeServiceBlockingStub exchangeServiceStub(ManagedChannel coreChannel) {
        return ExchangeServiceGrpc.newBlockingStub(coreChannel);
    }
}
