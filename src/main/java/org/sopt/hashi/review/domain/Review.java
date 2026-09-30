package org.sopt.hashi.review.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLDelete;
import org.sopt.hashi.BaseTimeEntity;

/**
 * 삭제는 soft delete(deleted=true). 방문 예약 목록이 삭제된 리뷰를 조회해 DELETED 상태 노출·재작성 차단에
 * 사용하므로 전역 필터(@SQLRestriction) 없이 조회 쿼리에만 deleted 조건을 명시한다.
 */
@Getter
@Entity
@Table(name = "review")
@SQLDelete(sql = "UPDATE review SET deleted = true WHERE id = ?")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Embedded
    private ReviewRating rating;

    @Column(name = "content", length = 1000, nullable = false)
    private String content;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "review_keyword", joinColumns = @JoinColumn(name = "review_id"))
    @OrderColumn(name = "display_order")
    @Column(name = "keyword", length = 30, nullable = false)
    private List<String> keywords = new ArrayList<>();

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ReviewImage> images = new ArrayList<>();

    private Review(Long reservationId, Long restaurantId, Long userId, int rating, String content) {
        this.reservationId = reservationId;
        this.restaurantId = restaurantId;
        this.userId = userId;
        this.rating = ReviewRating.from(rating);
        this.content = content;
        this.deleted = false;
    }

    public static Review create(
            Long reservationId,
            Long restaurantId,
            Long userId,
            int rating,
            String content
    ) {
        return new Review(reservationId, restaurantId, userId, rating, content);
    }

    public void replaceKeywords(List<String> keywords) {
        this.keywords.clear();
        if (keywords != null) {
            this.keywords.addAll(keywords);
        }
    }

    public void replaceImages(List<ReviewImage> images) {
        this.images.clear();
        if (images != null) {
            images.forEach(this::addImage);
        }
    }

    public void updateRatingAndContent(int rating, String content) {
        this.rating = ReviewRating.from(rating);
        this.content = content;
    }

    public int getRating() {
        return rating.value();
    }

    public boolean writtenBy(Long userId) {
        return this.userId.equals(userId);
    }

    public void softDelete() {
        this.deleted = true;
    }

    private void addImage(ReviewImage image) {
        image.assignReview(this);
        this.images.add(image);
    }
}
