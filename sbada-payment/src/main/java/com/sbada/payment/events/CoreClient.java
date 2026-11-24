package com.sbada.payment.events;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sbada.grpc.exchange.v1.ExchangeServiceGrpc;
import com.sbada.grpc.exchange.v1.GetExchangeRequest;
import com.sbada.grpc.user.v1.GetUsersRequest;
import com.sbada.grpc.user.v1.UserServiceGrpc;
import com.sbada.payment.common.ApiException;

import io.grpc.StatusRuntimeException;

/**
 * 메인 서버(회원 · 교환 서비스)와 이야기하는 클라이언트.
 * 2026-11-25 MSA 2단계: 묻는 일은 배달부(gRPC :9097 UserService · ExchangeService), 알리는 일은 허브(Kafka, OutboxRelay).
 * 예전 HTTP 이벤트 전달(sendEvent → /internal/events)은 sbada.events.transport=http 일 때만 쓴다.
 * 서비스끼리는 내부 비밀 토큰으로 서로를 확인한다(앱 · 외부에서는 부를 수 없음).
 */
@Component
public class CoreClient {

    public static final String HEADER = "X-Internal-Token";
    private static final long DEADLINE_MS = 5000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final InternalSecret secret;
    private final boolean verifyUsers;
    private final UserServiceGrpc.UserServiceBlockingStub users;
    private final ExchangeServiceGrpc.ExchangeServiceBlockingStub exchanges;

    public CoreClient(@Value("${sbada.core.url}") String baseUrl, InternalSecret secret,
                      @Value("${sbada.core.verify-users:true}") boolean verifyUsers,
                      UserServiceGrpc.UserServiceBlockingStub users,
                      ExchangeServiceGrpc.ExchangeServiceBlockingStub exchanges) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
        this.verifyUsers = verifyUsers;
        this.users = users;
        this.exchanges = exchanges;
    }

    /** 메인 서버 확인을 켰는지(테스트에서는 끄고 결제 서비스만 따로 시험한다) */
    public boolean verifies() {
        return verifyUsers;
    }

    /** 메인 서버 → 결제 서비스 내부 HTTP 호출(/internal/escrow/**)의 토큰 확인 */
    public void checkInternal(String given) {
        if (!secret.matches(given)) {
            throw new ApiException(401, "INTERNAL_AUTH_FAILED", "내부 서비스 인증에 실패했습니다.");
        }
    }

    /**
     * 교환 서비스(메인 서버)가 아는 교환 정보. 추가 금액 · 누가 내는지는 메인 서버가 정하고, 결제 서비스는 그대로 따른다.
     *  status: REQUESTED · ACCEPTED · REJECTED · CANCELED
     *  payerId · payeeId: 추가 금액 조건이 없으면 null
     */
    public record ExchangeInfo(long id, String status, long extraAmount, Long payerId, Long payeeId,
                               String paymentStatus) {
    }

    /** 교환 정보 묻기(배달부 gRPC GetExchange). 없으면 Optional.empty(), 메인 서버가 꺼져 있으면 503 */
    public Optional<ExchangeInfo> findExchange(long exchangeId) {
        try {
            var x = exchanges.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getExchange(GetExchangeRequest.newBuilder().setExchangeId(exchangeId).build());
            if (!x.getFound()) {
                return Optional.empty();
            }
            return Optional.of(new ExchangeInfo(x.getId(), x.getStatus(), x.getExtraAmount(),
                    x.getPayerId() == 0 ? null : x.getPayerId(), x.getPayeeId() == 0 ? null : x.getPayeeId(),
                    x.getPaymentStatus()));
        } catch (StatusRuntimeException e) {
            throw new ApiException(503, "CORE_SERVICE_DOWN", "교환 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 추가 요금을 받을 사람이 실제 회원인지 회원 서비스에 묻는다(배달부 gRPC GetUsers) */
    public void requireUser(long userId) {
        if (!verifyUsers) {
            return;
        }
        List<?> found;
        try {
            found = users.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .getUsers(GetUsersRequest.newBuilder().addIds(userId).build()).getUsersList();
        } catch (StatusRuntimeException e) {
            throw new ApiException(503, "CORE_SERVICE_DOWN", "회원 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (found.isEmpty()) {
            throw ApiException.notFound("USER_NOT_FOUND", "받는 사람을 찾을 수 없습니다.");
        }
    }

    /** [예전 방식] 이벤트를 HTTP 로 전달(sbada.events.transport=http). 2xx 면 true */
    public boolean sendEvent(String json) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/events"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header(HEADER, secret.value())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
