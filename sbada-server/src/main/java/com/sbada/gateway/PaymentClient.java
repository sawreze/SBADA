package com.sbada.gateway;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sbada.common.ApiException;
import com.sbada.grpc.payment.v1.EscrowCommand;
import com.sbada.grpc.payment.v1.EscrowResult;
import com.sbada.grpc.payment.v1.EscrowServiceGrpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 메인 서버(교환 서비스) → 결제 서버 안전결제 정산 · 환불 (2026-11-25 MSA 2단계: HTTP → 배달부 gRPC :9098)
 *  release(교환 완료 → 받는 사람 지갑으로) · refund(교환 거절 · 취소 → 낸 사람에게). 결제 서버는 같은 호출이 두 번 와도
 *  한 번만 반영한다(멱등) → 실패하면 그냥 다시 불러도 안전하다.
 * 결제 서버의 업무 오류는 gRPC 상태 + 설명 "코드|메시지" 로 온다 → 앱에는 예전과 같은 {code, message} 로 전달.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);
    private static final long DEADLINE_MS = 10_000;

    private final EscrowServiceGrpc.EscrowServiceBlockingStub stub;

    public PaymentClient(EscrowServiceGrpc.EscrowServiceBlockingStub stub) {
        this.stub = stub;
    }

    /** 정산. 결과 예: {"status":"RELEASED","amount":30000} */
    public Map<String, Object> release(long exchangeId) {
        return call(true, exchangeId, null);
    }

    /** 환불. 결과 예: {"status":"REFUNDED", …} */
    public Map<String, Object> refund(long exchangeId, String reason) {
        return call(false, exchangeId, reason);
    }

    /**
     * 환불을 '시도만' 한다(교환 취소 · 거절 직후, 커밋 뒤에 부름). 실패해도 예외를 던지지 않는다:
     * 결제 서비스의 대사 작업(1분마다)이 교환 상태를 보고 다시 환불하므로 돈이 묶여 있지 않는다.
     */
    public void refundQuietly(long exchangeId, String reason) {
        try {
            refund(exchangeId, reason);
        } catch (ApiException e) {
            log.warn("환불 요청 실패(결제 서비스가 나중에 다시 처리): 교환 {} — {}", exchangeId, e.getCode());
        }
    }

    private Map<String, Object> call(boolean release, long exchangeId, String reason) {
        EscrowCommand cmd = EscrowCommand.newBuilder().setExchangeId(exchangeId)
                .setReason(reason == null ? "" : reason).build();
        try {
            var s = stub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS);
            EscrowResult r = release ? s.release(cmd) : s.refund(cmd);
            return Map.of("status", r.getStatus(), "amount", r.getAmount(), "fee", r.getFee());
        } catch (StatusRuntimeException e) {
            throw toApi(e);
        }
    }

    static ApiException toApi(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        String desc = e.getStatus().getDescription();
        if (desc != null && desc.contains("|") && code != Status.Code.UNAVAILABLE && code != Status.Code.DEADLINE_EXCEEDED) {
            String c = desc.substring(0, desc.indexOf('|'));
            String m = desc.substring(desc.indexOf('|') + 1);
            int http = switch (code) {
                case NOT_FOUND -> 404;
                case PERMISSION_DENIED -> 403;
                case INVALID_ARGUMENT -> 400;
                case UNAUTHENTICATED -> 503; // 서버끼리 토큰이 안 맞음 = 설정 문제
                default -> 409;
            };
            return new ApiException(http, c, m);
        }
        return new ApiException(503, "PAYMENT_SERVICE_DOWN",
                "결제 서비스가 꺼져 있어요. 결제 서버를 켠 뒤 다시 시도해 주세요. (교환 · 채팅은 그대로 쓸 수 있어요)");
    }
}
