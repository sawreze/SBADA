package com.sbada.legacy;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.item.ItemRepository;

/** 물품 서버 상태 확인(로그인 불필요). 메인 서버 /api/health 가 이것을 함께 보여 준다 */
@RestController
public class HealthController {

    private final ItemRepository items;

    public HealthController(ItemRepository items) {
        this.items = items;
    }

    @GetMapping("/api/item-service/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "sbada-item", "items", items.count());
    }
}
