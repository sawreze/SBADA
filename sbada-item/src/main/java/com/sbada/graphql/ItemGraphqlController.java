package com.sbada.graphql;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import com.sbada.common.ApiException;
import com.sbada.common.Category;
import com.sbada.item.ItemDtos;
import com.sbada.item.ItemLikeRepository;
import com.sbada.item.ItemLikeService;
import com.sbada.item.ItemRepository;
import com.sbada.item.ItemService;
import com.sbada.item.ItemStatus;

/**
 * 물품 목록 GraphQL (2026-11-25 사용자 요청: "물품 목록 가져올 땐 GraphQL")
 *
 * [REST 와 무엇이 다른가]
 *  - REST GET /api/items 는 서버가 정한 모양(ItemView 전체)을 항상 다 보낸다.
 *  - GraphQL 은 앱이 { items { id title price } } 처럼 필요한 칸만 적어 보내면 그것만 온다 → 목록 화면 데이터가 가볍다.
 *  - likedByMe 는 @BatchMapping(DataLoader): 목록이 30개여도 '내가 찜한 물건' 질의는 한 번이다(N+1 방지).
 * 검색 규칙 · 보류(HELD) 물건 숨기기 등 업무 규칙은 REST 와 같은 ItemService 를 그대로 쓴다.
 */
@Controller
public class ItemGraphqlController {

    private final ItemService items;
    private final ItemLikeService likes;
    private final ItemLikeRepository likeRows;
    private final ItemRepository itemRows;

    public ItemGraphqlController(ItemService items, ItemLikeService likes, ItemLikeRepository likeRows,
                                 ItemRepository itemRows) {
        this.items = items;
        this.likes = likes;
        this.likeRows = likeRows;
        this.itemRows = itemRows;
    }

    @QueryMapping
    public List<ItemDtos.ItemView> items(@Argument String category, @Argument String q, @Argument String area,
                                         @Argument Integer limit) {
        int n = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return items.list(category, q, area, n).items();
    }

    @QueryMapping
    public ItemDtos.ItemView item(@Argument int id) {
        Long viewer = viewerId();
        return itemRows.findById((long) id)
                .filter(i -> i.getStatus() != ItemStatus.HIDDEN)
                .filter(i -> i.getStatus() != ItemStatus.HELD || (viewer != null && i.isOwnedBy(viewer)))
                .map(ItemDtos.ItemView::from)
                .orElse(null);
    }

    @QueryMapping
    public List<ItemDtos.ItemView> myItems() {
        return items.mine(requireViewer()).items();
    }

    @QueryMapping
    public List<ItemDtos.ItemView> myLikes() {
        return likes.mine(requireViewer()).items();
    }

    @QueryMapping
    public int itemCount(@Argument String area) {
        return (int) items.count(area);
    }

    @QueryMapping
    public List<ItemDtos.CategoryView> categories() {
        return Arrays.stream(Category.values())
                .map(c -> new ItemDtos.CategoryView(c.name(), c.label(), c.emoji(), c.basePrice())).toList();
    }

    /** 목록의 모든 물건에 대해 '내가 찜했는지'를 한 번의 질의로 */
    @BatchMapping(typeName = "Item", field = "likedByMe")
    public Map<ItemDtos.ItemView, Boolean> likedByMe(List<ItemDtos.ItemView> views) {
        Long viewer = viewerId();
        Map<ItemDtos.ItemView, Boolean> out = new HashMap<>();
        Set<Long> liked = new HashSet<>();
        if (viewer != null && !views.isEmpty()) {
            liked.addAll(likeRows.likedItemIds(viewer, views.stream().map(ItemDtos.ItemView::id).toList()));
        }
        for (ItemDtos.ItemView v : views) {
            out.put(v, liked.contains(v.id()));
        }
        return out;
    }

    private static Long viewerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }

    private static long requireViewer() {
        Long id = viewerId();
        if (id == null) {
            throw new ApiException(401, "AUTH_REQUIRED", "로그인이 필요합니다.");
        }
        return id;
    }
}
