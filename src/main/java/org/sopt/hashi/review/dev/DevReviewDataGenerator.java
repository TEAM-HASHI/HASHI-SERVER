package org.sopt.hashi.review.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.sopt.hashi.restaurant.RestaurantPort;
import org.sopt.hashi.review.domain.Review;
import org.sopt.hashi.review.domain.ReviewKeyword;
import org.sopt.hashi.review.domain.ReviewRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 더미 리뷰 생성 — local·dev 프로필에서만 빈이 등록된다(운영에는 존재 자체가 없음).
 * 실제 작성 흐름과 같게 리뷰 저장과 식당 평점 통계 반영(RestaurantPort)을 함께 수행한다.
 * 포인트 적립은 더미 데이터에 불필요한 부수효과라 생략한다.
 */
@Profile({"local", "dev"})
@Service
public class DevReviewDataGenerator {

    private static final List<String> CONTENTS = List.of(
            "더미 리뷰입니다. 분위기가 좋고 음식이 맛있었어요.",
            "더미 리뷰입니다. 웨이팅이 있었지만 기다린 보람이 있었습니다.",
            "더미 리뷰입니다. 직원분들이 친절하고 매장이 깨끗했어요.",
            "더미 리뷰입니다. 가성비가 좋아서 재방문 의사 있습니다.",
            "더미 리뷰입니다. 현지 감성이 물씬 나는 곳이었어요.");

    private final ReviewRepository reviewRepository;
    private final RestaurantPort restaurantPort;

    public DevReviewDataGenerator(ReviewRepository reviewRepository, RestaurantPort restaurantPort) {
        this.reviewRepository = reviewRepository;
        this.restaurantPort = restaurantPort;
    }

    /** 방문 완료 예약마다 리뷰 1건씩 생성하고(평점 3~5 무작위, 통계 반영 포함) reviewId 목록을 반환한다. */
    @Transactional
    public List<Long> createReviews(Long restaurantId, List<DummyReviewTarget> targets) {
        List<Long> reviewIds = new ArrayList<>();
        for (DummyReviewTarget target : targets) {
            int rating = ThreadLocalRandom.current().nextInt(3, 6);
            Review review = Review.create(
                    target.reservationId(), restaurantId, target.userId(), rating, randomContent());
            review.replaceKeywords(randomKeywords());
            reviewIds.add(reviewRepository.save(review).getId());
            restaurantPort.increaseReviewStatistics(restaurantId, rating);
        }
        return reviewIds;
    }

    /**
     * 작성 후 삭제된(soft delete) 더미 리뷰 1건을 생성하고 reviewId를 반환한다.
     * 실제 흐름은 작성 시 통계 증가·삭제 시 차감으로 합이 0이므로 통계 반영을 생략한다.
     */
    @Transactional
    public Long createDeletedReview(Long restaurantId, DummyReviewTarget target) {
        int rating = ThreadLocalRandom.current().nextInt(3, 6);
        Review review = Review.create(
                target.reservationId(), restaurantId, target.userId(), rating, randomContent());
        review.replaceKeywords(randomKeywords());
        review.softDelete();
        return reviewRepository.save(review).getId();
    }

    private String randomContent() {
        return CONTENTS.get(ThreadLocalRandom.current().nextInt(CONTENTS.size()));
    }

    /** 유효 키워드 풀에서 앞쪽 2개를 무작위 시작점으로 골라 화면 노출까지 자연스럽게 한다. */
    private List<String> randomKeywords() {
        ReviewKeyword[] keywords = ReviewKeyword.values();
        int start = ThreadLocalRandom.current().nextInt(keywords.length);
        return List.of(
                keywords[start].name(),
                keywords[(start + 1) % keywords.length].name());
    }
}
