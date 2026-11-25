package com.sbada.review;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sbada.common.ApiException;
import com.sbada.exchange.DealStage;
import com.sbada.exchange.ExchangeRepository;
import com.sbada.exchange.ExchangeRequest;
import com.sbada.notification.NotificationService;
import com.sbada.notification.NotificationType;
import com.sbada.user.AppUser;
import com.sbada.user.AppUserRepository;

/**
 * 거래 후기 (2026-11-25 사용자 요청).
 *  - 두 물건 모두 배송 완료(DealStage.COMPLETED)된 교환의 당사자만, 상대에게 한 번 쓸 수 있다(별점 1~5 + 300자).
 *  - 프로필(GET /api/users/{id}/reviews)에서 물건 목록 아래에 후기 목록 · 평균 별점으로 보인다.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final ExchangeRepository exchanges;
    private final AppUserRepository users;
    private final NotificationService notifications;

    public ReviewService(ReviewRepository reviews, ExchangeRepository exchanges, AppUserRepository users,
                         NotificationService notifications) {
        this.reviews = reviews;
        this.exchanges = exchanges;
        this.users = users;
        this.notifications = notifications;
    }

    public record WriterView(long id, String nickname, String profileImageUrl) {
    }

    public record ReviewView(long id, long exchangeId, int rating, String content, String createdAt, WriterView writer) {
        static ReviewView from(Review r) {
            AppUser w = r.getWriter();
            String photo = w.getProfileImageId() == null ? null : "/api/images/" + w.getProfileImageId();
            return new ReviewView(r.getId(), r.getExchangeId(), r.getRating(), r.getContent(), r.getCreatedAt().toString(),
                    new WriterView(w.getId(), w.getNickname(), photo));
        }
    }

    public record ReviewList(long userId, long count, double average, List<ReviewView> reviews) {
    }

    @Transactional
    public ReviewView write(long exchangeId, long writerId, int rating, String content) {
        ExchangeRequest e = exchanges.findById(exchangeId)
                .orElseThrow(() -> ApiException.notFound("EXCHANGE_NOT_FOUND", "교환을 찾을 수 없어요."));
        if (!e.isParticipant(writerId)) {
            throw ApiException.forbidden("NOT_PARTICIPANT", "거래한 사람만 후기를 쓸 수 있어요.");
        }
        if (e.getDealStage() != DealStage.COMPLETED) {
            throw ApiException.conflict("NOT_COMPLETED", "두 물건 모두 도착해 거래가 끝난 뒤에 후기를 쓸 수 있어요.");
        }
        if (e.hasReviewed(writerId) || reviews.existsByExchangeIdAndWriterId(exchangeId, writerId)) {
            throw ApiException.conflict("ALREADY_REVIEWED", "이 거래의 후기는 이미 남겼어요.");
        }
        if (rating < 1 || rating > 5) {
            throw ApiException.badRequest("INVALID_RATING", "별점은 1~5개로 골라 주세요.");
        }
        String text = content == null || content.isBlank() ? null : content.trim();
        if (text != null && text.length() > 300) {
            throw ApiException.badRequest("REVIEW_TOO_LONG", "후기는 300자까지 쓸 수 있어요.");
        }
        AppUser writer = users.findById(writerId).orElseThrow();
        AppUser target = users.findById(e.otherOf(writerId)).orElseThrow();
        Review saved = reviews.save(new Review(exchangeId, writer, target, rating, text));
        e.markReviewed(writerId);
        notifications.notify(target.getId(), NotificationType.REVIEW_RECEIVED, "거래 후기가 도착했어요",
                String.format("%s님이 별점 %d개 후기를 남겼어요.", writer.getNickname(), rating), "USER", target.getId());
        return ReviewView.from(saved);
    }

    @Transactional(readOnly = true)
    public ReviewList listFor(long userId) {
        if (!users.existsById(userId)) {
            throw ApiException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없어요.");
        }
        List<ReviewView> list = reviews.findForTarget(userId, PageRequest.of(0, 50)).stream().map(ReviewView::from).toList();
        long count = reviews.countByTargetId(userId);
        double avg = Math.round(reviews.averageFor(userId) * 10) / 10.0;
        return new ReviewList(userId, count, avg, list);
    }
}
