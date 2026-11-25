package com.sbada.review;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 거래 후기 (2026-11-25)
 *  POST /api/exchanges/{id}/review  (🔒) {rating 1~5, content ≤300} → 201 ReviewView (거래 완료 후 한 번)
 *  GET  /api/users/{id}/reviews     (공개) {userId, count, average, reviews:[{id, rating, content, createdAt, writer}]}
 */
@RestController
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    public record WriteRequest(
            @NotNull(message = "별점을 골라 주세요.") @Min(value = 1, message = "별점은 1~5개예요.") @Max(value = 5, message = "별점은 1~5개예요.") Integer rating,
            @Size(max = 300, message = "후기는 300자까지 쓸 수 있어요.") String content) {
    }

    @PostMapping("/api/exchanges/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewService.ReviewView write(@PathVariable long id, @Valid @RequestBody WriteRequest body, Authentication auth) {
        return reviews.write(id, (Long) auth.getPrincipal(), body.rating(), body.content());
    }

    @GetMapping("/api/users/{id}/reviews")
    public ReviewService.ReviewList list(@PathVariable long id) {
        return reviews.listFor(id);
    }
}
