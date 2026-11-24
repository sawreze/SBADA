package com.sbada.seller;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.sbada.common.ApiException;
import com.sbada.grpc.CoreGrpcClient;

/**
 * 판매자 사본 관리. 없으면 메인 서버에 gRPC 로 물어보고 저장, 허브 이벤트가 오면 고친다.
 *  require(id)    : 꼭 있어야 할 때(물건 등록 · 찜 · 분석). 메인 서버에도 없으면 401(토큰은 맞지만 탈퇴 등)
 *  ensure(ids)    : 여러 명을 한 번에(목록)
 *  upsert(...)    : 허브(sbada.user.events)에서 USER_UPDATED 가 오면
 */
@Service
public class SellerDirectory {

    private final SellerRepository sellers;
    private final CoreGrpcClient core;
    private final TransactionTemplate tx;

    public SellerDirectory(SellerRepository sellers, CoreGrpcClient core, TransactionTemplate tx) {
        this.sellers = sellers;
        this.core = core;
        this.tx = tx;
    }

    public Seller require(long userId) {
        Seller s = sellers.findById(userId).orElse(null);
        if (s != null) {
            return s;
        }
        ensure(List.of(userId));
        return sellers.findById(userId)
                .orElseThrow(() -> new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다."));
    }

    /** 없는 사람만 메인 서버에 한 번에 물어서 저장한다(N+1 방지). 메인 서버가 꺼져 있으면 있는 사람만 */
    public Map<Long, Seller> ensure(Collection<Long> ids) {
        Map<Long, Seller> found = new HashMap<>();
        sellers.findAllById(ids).forEach(s -> found.put(s.getId(), s));
        Set<Long> missing = new HashSet<>(ids);
        missing.removeAll(found.keySet());
        if (!missing.isEmpty()) {
            for (CoreGrpcClient.UserInfo u : core.getUsers(missing)) {
                Seller saved = upsert(u.id(), u.nickname(), u.region(), u.secondRegion());
                found.put(saved.getId(), saved);
            }
        }
        return found;
    }

    public Seller upsert(long id, String nickname, String region, String secondRegion) {
        return tx.execute(status -> {
            Seller s = sellers.findById(id).orElse(null);
            if (s == null) {
                return sellers.save(new Seller(id, nickname, region, secondRegion));
            }
            s.apply(nickname, region, secondRegion);
            return s;
        });
    }
}
