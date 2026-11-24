package com.sbada.item;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 찜(관심목록) API — 모두 로그인 필요(🔒)
 *
 * | 이름        | METHOD · 경로                    | 응답                               |
 * |-------------|----------------------------------|------------------------------------|
 * | 찜하기      | POST   /api/items/{id}/like      | {"itemId":7,"liked":true}          |
 * | 찜 해제     | DELETE /api/items/{id}/like      | {"itemId":7,"liked":false}         |
 * | 내 관심목록 | GET    /api/me/likes             | {"items":[ItemView…]} (최근 찜 순) |
 *
 * 오류: 토큰 없음 401 AUTH_REQUIRED · 없는/내린 물건 404 ITEM_NOT_FOUND · 내 물건 찜 400 SELF_LIKE
 */
@RestController
public class ItemLikeController {

    private final ItemLikeService service;

    public ItemLikeController(ItemLikeService service) {
        this.service = service;
    }

    @PostMapping("/api/items/{id}/like")
    public ItemLikeService.LikeState like(@PathVariable long id, Authentication auth) {
        return service.like(id, (Long) auth.getPrincipal());
    }

    @DeleteMapping("/api/items/{id}/like")
    public ItemLikeService.LikeState unlike(@PathVariable long id, Authentication auth) {
        return service.unlike(id, (Long) auth.getPrincipal());
    }

    @GetMapping("/api/me/likes")
    public ItemDtos.ItemList mine(Authentication auth) {
        return service.mine((Long) auth.getPrincipal());
    }
}
