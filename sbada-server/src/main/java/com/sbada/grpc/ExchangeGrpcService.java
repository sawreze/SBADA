package com.sbada.grpc;

import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.exchange.ExchangeRepository;
import com.sbada.grpc.exchange.v1.ExchangeInfo;
import com.sbada.grpc.exchange.v1.ExchangeServiceGrpc;
import com.sbada.grpc.exchange.v1.GetExchangeRequest;

import io.grpc.stub.StreamObserver;

/**
 * 배달부(gRPC) 교환 서비스 — 결제 서버가 안전결제 전에 '누가 · 누구에게 · 얼마'를 확인할 때 부른다 (:9097, 내부 토큰)
 * 예전 HTTP GET /internal/exchanges/{id} 와 같은 내용.
 */
@GrpcService
public class ExchangeGrpcService extends ExchangeServiceGrpc.ExchangeServiceImplBase {

    private final ExchangeRepository exchanges;
    private final TransactionTemplate tx;

    public ExchangeGrpcService(ExchangeRepository exchanges, TransactionTemplate tx) {
        this.exchanges = exchanges;
        this.tx = tx;
    }

    @Override
    public void getExchange(GetExchangeRequest request, StreamObserver<ExchangeInfo> out) {
        ExchangeInfo res = tx.execute(s -> exchanges.findById(request.getExchangeId())
                .map(e -> ExchangeInfo.newBuilder().setFound(true).setId(e.getId())
                        .setStatus(e.getStatus().name())
                        .setExtraAmount(e.getExtraAmount())
                        .setPayerId(e.payerId() == null ? 0 : e.payerId())
                        .setPayeeId(e.payeeId() == null ? 0 : e.payeeId())
                        .setPaymentStatus(e.getPaymentStatus())
                        .setRequesterId(e.getRequester().getId())
                        .setOwnerId(e.getTargetOwner().getId())
                        .build())
                .orElse(ExchangeInfo.newBuilder().setFound(false).build()));
        out.onNext(res);
        out.onCompleted();
    }
}
