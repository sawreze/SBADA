package com.sbada.item;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sbada.common.Category;
import com.sbada.common.ItemCondition;

/**
 * ■ 카테고리·상태 목록   GET /api/categories   (공개)
 *
 * 응답 200
 *   {
 *     "categories": [
 *       {"code":"DIGITAL","label":"디지털기기","emoji":"📱","basePrice":150000},
 *       {"code":"APPLIANCE","label":"생활가전","emoji":"🔌","basePrice":80000}, …14개
 *     ],
 *     "conditions": [
 *       {"code":"NEW","label":"새 상품","factor":1.0}, {"code":"GOOD","label":"사용감 적음","factor":0.7}, …5개
 *     ]
 *   }
 *
 * [프로그래밍 설명]
 *  - DB 를 읽지 않고 enum 값으로 바로 응답을 만든다(카테고리 목록은 코드에 고정).
 *  - Arrays.stream(배열).map(…).toList(): enum 배열 → DTO 목록 변환.
 *  - Map.of(키, 값, …): 작은 고정 Map 을 만든다. Jackson 이 Map 도 그대로 JSON 객체로 바꾼다.
 *    Map.<String, Object>of(…) 의 <String, Object> 는 값 타입이 섞여 있어(문자열·숫자) 타입을 직접 알려 준 것.
 */
@RestController
public class CategoryController {

    /** 온보딩·등록 화면이 쓰는 카테고리와 상태 목록 */
    @GetMapping("/api/categories")
    public Map<String, Object> categories() {
        List<ItemDtos.CategoryView> categories = Arrays.stream(Category.values())
                .map(c -> new ItemDtos.CategoryView(c.name(), c.label(), c.emoji(), c.basePrice())).toList();
        List<Map<String, Object>> conditions = Arrays.stream(ItemCondition.values())
                .map(c -> Map.<String, Object>of("code", c.name(), "label", c.label(), "factor", c.factor()))
                .toList();
        return Map.of("categories", categories, "conditions", conditions);
    }
}
