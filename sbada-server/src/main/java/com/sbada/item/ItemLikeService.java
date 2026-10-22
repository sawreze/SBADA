package com.sbada.item;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.ApiException;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;

/**
 * 찜(관심목록). 상세 화면 왼쪽 아래 하트 → 찜하기 / 해제, 계정정보 > 관심목록.
 * 권한 순서는 다른 API 와 같다: 로그인(401) → 물건 있음(404) → 내 물건이 아님(400 SELF_LIKE).
 * 같은 요청을 두 번 보내도 결과가 같다(이미 찜했으면 그대로, 이미 해제했으면 그대로).
 * 처음 찜할 때만 물건 주인에게 ITEM_LIKED 알림(V14, 주인이 알림 설정에서 '찜 알림'을 끄면 푸시 · 배너 없음).
 */
@Service
public class ItemLikeService {

    private final ItemLikeRepository likes;
    private final ItemService items;
    private final AppUserRepository users;
    private final NotificationService notifications;

    public ItemLikeService(ItemLikeRepository likes, ItemService items, AppUserRepository users,
                           NotificationService notifications) {
        this.likes = likes;
        this.items = items;
        this.users = users;
        this.notifications = notifications;
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
            AppUser fan = users.getReferenceById(userId);
            likes.save(new ItemLike(fan, item));
            // 처음 찜할 때만 알림(두 번 눌러도 한 번). 같은 트랜잭션 → 찜 저장이 취소되면 알림도 취소
            notifications.notify(item.getOwner().getId(), NotificationType.ITEM_LIKED, "누가 내 물건을 찜했어요",
                    fan.getNickname() + "님이 '" + item.getTitle() + "'을(를) 관심목록에 담았어요.", "ITEM", itemId);
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
