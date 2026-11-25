package com.sbada.shipping;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.exchange.ExchangeDtos;
import com.sbada.exchange.ShippingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 운송장 · 배송 (2026-11-25 사용자 요청)
 *  GET  /api/shipping/carriers               (공개) 택배사 목록 {"carriers":[{"code":"04","name":"CJ대한통운"}…], "realApi":true}
 *  POST /api/exchanges/{id}/shipment         (🔒) {carrierCode, invoiceNo} → 접수 확인되면 ExchangeView, 아니면 400 INVALID_INVOICE
 *  POST /api/exchanges/{id}/shipment/refresh (🔒) 배송 현황 지금 다시 묻기 → ExchangeView
 */
@RestController
public class ShippingController {

    private final ShippingService shipping;
    private final TrackingService tracking;

    public ShippingController(ShippingService shipping, TrackingService tracking) {
        this.shipping = shipping;
        this.tracking = tracking;
    }

    public record ShipmentRequest(
            @NotBlank(message = "택배사를 골라 주세요.") String carrierCode,
            @NotBlank(message = "운송장 번호를 입력해 주세요.") @Size(max = 30, message = "운송장 번호가 너무 길어요.") String invoiceNo) {
    }

    @GetMapping("/api/shipping/carriers")
    public Map<String, Object> carriers() {
        List<Carrier> list = tracking.carriers();
        return Map.of("carriers", list, "realApi", tracking.hasRealApi());
    }

    @PostMapping("/api/exchanges/{id}/shipment")
    public ExchangeDtos.ExchangeView submit(@PathVariable long id, @Valid @RequestBody ShipmentRequest body, Authentication auth) {
        return shipping.submit(id, (Long) auth.getPrincipal(), body.carrierCode(), body.invoiceNo());
    }

    @PostMapping("/api/exchanges/{id}/shipment/refresh")
    public ExchangeDtos.ExchangeView refresh(@PathVariable long id, Authentication auth) {
        return shipping.refresh(id, (Long) auth.getPrincipal());
    }
}
