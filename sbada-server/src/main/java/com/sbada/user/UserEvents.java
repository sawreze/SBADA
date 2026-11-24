package com.sbada.user;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.events.EventPublisher;
import com.sbada.events.Topics;

/**
 * 회원 정보가 바뀌면 허브(Kafka sbada.user.events)로 알린다 (2026-11-25 MSA 2단계)
 * 물품 서버는 판매자 사본(닉네임 · 동네)을 갖고 있어서, 동네 · 닉네임을 바꾸면 목록 · 검색에도 바로 반영돼야 한다.
 * 저장과 이벤트 기록을 한 트랜잭션에(아웃박스) → 저장만 되고 알림이 빠지는 일이 없다.
 */
@Component
public class UserEvents {

    private final AppUserRepository users;
    private final EventPublisher events;

    public UserEvents(AppUserRepository users, EventPublisher events) {
        this.users = users;
        this.events = events;
    }

    @Transactional
    public AppUser saveAndAnnounce(AppUser user) {
        AppUser saved = users.save(user);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", saved.getId());
        data.put("nickname", saved.getNickname());
        data.put("region", saved.getRegion());
        data.put("secondRegion", saved.getSecondRegion());
        events.publish(Topics.USER, saved.getId(), "USER_UPDATED", data);
        return saved;
    }
}
