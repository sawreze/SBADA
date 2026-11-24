package com.sbada.payment.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.sbada.grpc.payment.v1.EscrowCommand;
import com.sbada.grpc.payment.v1.EscrowResult;
import com.sbada.grpc.payment.v1.EscrowServiceGrpc;
import com.sbada.payment.common.ApiException;
import com.sbada.payment.wallet.EscrowService;
import com.sbada.payment.wallet.WalletDtos;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * 배달부(gRPC) 안전결제 서비스 — 메인 서버(교환)가 부른다 (2026-11-25 MSA 2단계, :9098, 내부 토큰 필수)
 *  Release: 교환 완료 → 받는 사람 지갑으로 정산.  Refund: 교환 거절 · 취소 → 낸 사람에게 환불.
 *  같은 호출이 두 번 와도 한 번만 반영(EscrowService 가 멱등). 업무 오류는 상태 + 설명 "코드|메시지".
 */
@GrpcService
public class EscrowGrpcService extends EscrowServiceGrpc.EscrowServiceImplBase {

    private final EscrowService escrow;

    public EscrowGrpcService(EscrowService escrow) {
        this.escrow = escrow;
    }

    @Override
    public void release(EscrowCommand request, StreamObserver<EscrowResult> out) {
        run(out, () -> escrow.release(request.getExchangeId()));
    }

    @Override
    public void refund(EscrowCommand request, StreamObserver<EscrowResult> out) {
        run(out, () -> escrow.refund(request.getExchangeId(), request.getReason()));
    }

    private static void run(StreamObserver<EscrowResult> out, java.util.function.Supplier<WalletDtos.EscrowView> work) {
        try {
            WalletDtos.EscrowView v = work.get();
            out.onNext(EscrowResult.newBuilder().setStatus(v.status()).setAmount(v.amount()).setFee(v.fee()).build());
            out.onCompleted();
        } catch (ApiException e) {
            Status s = switch (e.getStatus()) {
                case 404 -> Status.NOT_FOUND;
                case 400 -> Status.INVALID_ARGUMENT;
                case 403 -> Status.PERMISSION_DENIED;
                case 503 -> Status.UNAVAILABLE;
                default -> Status.FAILED_PRECONDITION;
            };
            out.onError(s.withDescription(e.getCode() + "|" + e.getMessage()).asRuntimeException());
        }
    }
}
