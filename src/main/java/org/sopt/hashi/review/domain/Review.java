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
import org.sopt.hashi.BaseTimeEntity;

@Getter
@Entity
@Table(name = "review")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 레거시 리뷰는 null일 수 있고, 신규 리뷰는 예약 1건을 기준으로 생성한다. */
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "writer_id", nullable = false)
    private Long writerId;

    @Embedded
    private ReviewRating rating;

    @Column(name = "content", length = 1000, nullable = false)
    private String content;

    @Column(name = "active", nullable = false)
    private boolean active;

    @BatchSize(size = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "review_keyword", joinColumns = @JoinColumn(name = "review_id"))
    @OrderColumn(name = "display_order")
    @Column(name = "keyword", length = 50, nullable = false)
    private List<String> keywords = new ArrayList<>();

    @BatchSize(size = 100)
    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ReviewImage> images = new ArrayList<>();

    private Review(Long reservationId, Long restaurantId, Long writerId, int rating, String content) {
        this.reservationId = reservationId;
        this.restaurantId = restaurantId;
        this.writerId = writerId;
        this.rating = ReviewRating.from(rating);
        this.content = content;
        this.active = true;
    }

    public static Review create(Long restaurantId, Long writerId, int rating, String content) {
        return new Review(null, restaurantId, writerId, rating, content);
    }

    public static Review create(
            Long reservationId,
            Long restaurantId,
            Long writerId,
            int rating,
            String content
    ) {
        return new Review(reservationId, restaurantId, writerId, rating, content);
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

    public int getRating() {
        return rating.value();
    }

    public boolean writtenBy(Long userId) {
        return writerId.equals(userId);
    }

    public void deactivate() {
        this.active = false;
    }

    private void addImage(ReviewImage image) {
        image.assignReview(this);
        this.images.add(image);
    }
}
