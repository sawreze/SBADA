package com.sbada.payment.wallet;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.payment.events.CoreClient;

import jakarta.validation.Valid;

/**
 * 메인 서버(교환 서비스)만 부르는 내부 API — 앱 · 게이트웨이를 거치지 않는다(게이트웨이는 /api/** 만 넘김).
 *  POST /internal/escrow/release {"exchangeId":7}                   교환 완료 → 받는 사람 지갑으로 정산
 *  POST /internal/escrow/refund  {"exchangeId":7,"reason":"…"}      교환 거절 · 취소 → 낸 사람에게 환불
 *  인증: 헤더 X-Internal-Token (메인 서버와 같은 비밀 파일). 같은 호출이 두 번 와도 결과는 한 번만 반영(멱등).
 */
@RestController
@RequestMapping("/internal/escrow")
public class EscrowInternalController {

    private final EscrowService escrow;
    private final CoreClient core;

    public EscrowInternalController(EscrowService escrow, CoreClient core) {
        this.escrow = escrow;
        this.core = core;
    }

    @PostMapping("/release")
    public WalletDtos.EscrowView release(@RequestHeader(value = CoreClient.HEADER, required = false) String token,
                                         @Valid @RequestBody WalletDtos.EscrowCommand in) {
        core.checkInternal(token);
        return escrow.release(in.exchangeId());
    }

    @PostMapping("/refund")
    public WalletDtos.EscrowView refund(@RequestHeader(value = CoreClient.HEADER, required = false) String token,
                                        @Valid @RequestBody WalletDtos.EscrowCommand in) {
        core.checkInternal(token);
        return escrow.refund(in.exchangeId(), in.reason());
    }
}
