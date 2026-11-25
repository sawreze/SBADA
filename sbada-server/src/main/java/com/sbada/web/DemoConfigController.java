package com.sbada.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 2026-11-25 시연 모드 여부(공개). 앱이 입력칸을 미리 채우고 '시연 결제'를 보여 줄지 정한다.
 *  GET /api/config/demo → {"easyFlow": true}   (dev 프로필: sbada.demo.easy-flow=true)
 */
@RestController
public class DemoConfigController {

    private final boolean easyFlow;

    public DemoConfigController(@Value("${sbada.demo.easy-flow:false}") boolean easyFlow) {
        this.easyFlow = easyFlow;
    }

    @GetMapping("/api/config/demo")
    public Map<String, Object> demo() {
        return Map.of("easyFlow", easyFlow);
    }
}
