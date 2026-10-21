package com.sbada.item;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.ApiException;
import com.sbada.user.AppUserRepository;

/**
 * 찜(관심목록). 상세 화면 왼쪽 아래 하트 → 찜하기 / 해제, 계정정보 > 관심목록.
 * 권한 순서는 다른 API 와 같다: 로그인(401) → 물건 있음(404) → 내 물건이 아님(400 SELF_LIKE).
 * 같은 요청을 두 번 보내도 결과가 같다(이미 찜했으면 그대로, 이미 해제했으면 그대로).
 */
@Service
public class ItemLikeService {

    private final ItemLikeRepository likes;
    private final ItemService items;
    private final AppUserRepository users;

    public ItemLikeService(ItemLikeRepository likes, ItemService items, AppUserRepository users) {
        this.likes = likes;
        this.items = items;
        this.users = users;
    }

    /** 응답: 지금 찜한 상태인지 */
    public record LikeState(long itemId, boolean liked) {
    }

    @Transactional
    public LikeState like(long itemId, long userId) {
        Item item = items.require(itemId);
        if (item.getStatus() == ItemStatus.HELD && !item.isOwnedBy(userId)) { // 보류 물건은 남에게 없는 물건
            throw ApiException.notFound("ITEM_NOT_FOUND", "물건을 찾을 수 없습니다.");
        }
        if (item.isOwnedBy(userId)) {
            throw ApiException.badRequest("SELF_LIKE", "내 물건은 찜할 수 없어요.");
        }
        if (likes.findByUserIdAndItemId(userId, itemId).isEmpty()) {
            likes.save(new ItemLike(users.getReferenceById(userId), item));
        }
        return new LikeState(itemId, true);
    }

    @Transactional
    public LikeState unlike(long itemId, long userId) {
        likes.findByUserIdAndItemId(userId, itemId).ifPresent(likes::delete);
        return new LikeState(itemId, false);
    }

    @Transactional(readOnly = true)
    public ItemDtos.ItemList mine(long userId) {
        List<ItemDtos.ItemView> list = likes.findMine(userId, ItemStatus.HIDDEN).stream()
                .filter(l -> l.getItem().getStatus() != ItemStatus.HELD) // 찜한 뒤 보류된 물건은 숨긴다
                .map(l -> ItemDtos.ItemView.from(l.getItem())).toList();
        return new ItemDtos.ItemList(list);
    }
}
