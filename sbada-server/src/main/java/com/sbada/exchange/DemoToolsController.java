package com.sbada.exchange;

import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.ApiException;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;

/**
 * 시연 도구 (2026-11-25 사용자 요청: 시연용 계정에서 모든 경우를 보여 주기). dev 프로필에서만 켜진다
 * (sbada.demo.tools-enabled=true — application-dev.properties). 운영 · 기본 설정에서는 이 주소들이 아예 없다(404).
 *   POST /api/demo/exchanges/{id}/deliver-now — 테스트 택배 두 운송장을 지금 배송 완료로(거래 완료 · 정산까지)
 *   POST /api/demo/exchanges/{id}/expire-now  — 운송장 입력 7일이 지난 것으로(기간 만료 · 취소 · 전액 환불)
 *   POST /api/demo/retire-me — 시연용 계정(demo · neighbor1~3@sbada.test)을 '지난 시연' 계정으로 바꿔 이메일을 비운다
 *                              → 시연계정_만들기 가 같은 이메일로 깨끗한 새 계정을 다시 만든다(지난 기록은 옛 계정에 남음)
 */
@RestController
@ConditionalOnProperty(name = "sbada.demo.tools-enabled", havingValue = "true")
public class DemoToolsController {

    /** 정리해도 되는 계정: 시연계정_만들기 가 쓰는 이메일만 */
    static final Pattern RETIRABLE = Pattern.compile("^(demo|neighbor[1-9])@sbada\\.test$");

    private final ShippingService shipping;
    private final AppUserRepository users;

    public DemoToolsController(ShippingService shipping, AppUserRepository users) {
        this.shipping = shipping;
        this.users = users;
    }

    @PostMapping("/api/demo/exchanges/{id}/deliver-now")
    public ExchangeDtos.ExchangeView deliverNow(@PathVariable long id, Authentication auth) {
        return shipping.demoDeliverNow(id, (Long) auth.getPrincipal());
    }

    @PostMapping("/api/demo/exchanges/{id}/expire-now")
    public ExchangeDtos.ExchangeView expireNow(@PathVariable long id, Authentication auth) {
        return shipping.demoExpireNow(id, (Long) auth.getPrincipal());
    }

    @PostMapping("/api/demo/retire-me")
    @Transactional
    public Map<String, Object> retireMe(Authentication auth) {
        AppUser user = users.findById((Long) auth.getPrincipal())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "계정을 찾을 수 없어요."));
        String email = user.getEmail();
        if (email == null || !RETIRABLE.matcher(email).matches()) {
            throw ApiException.forbidden("NOT_DEMO_ACCOUNT", "시연용 계정(demo · neighbor1~3@sbada.test)만 정리할 수 있어요.");
        }
        String tag = Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
        user.assignEmail("old" + user.getId() + "." + tag + "@sbada.test");
        user.changeProfile(cut("old" + user.getId() + "_" + tag, 20), "지난 시연");
        return Map.of("retired", true, "id", user.getId());
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
